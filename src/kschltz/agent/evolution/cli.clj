(ns kschltz.agent.evolution.cli
  "One-shot local entry point for bounded, worktree-isolated evolution."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as tools.cli]
            [integrant.core :as ig]
            [kschltz.agent.cli :as agent.cli]
            [kschltz.agent.evolution.discovery :as discovery]
            [kschltz.agent.evolution.ledger :as audit-ledger]
            [kschltz.agent.evolution.process :as process]
            [kschltz.agent.evolution.protocol :as proto]
            [kschltz.agent.evolution.schemas :as schemas]
            [kschltz.agent.evolution.state :as evolution.state]
            [kschltz.agent.evolution.supervisor :as supervisor]
            [kschltz.agent.evolution.wiring]
            [kschltz.agent.evolution.worktree :as worktree]
            [kschltz.agent.store.duckdb :as duckdb]
            [kschltz.agent.store.protocol :as store]))

(def ^:private options
  [[nil "--proposal PATH" "EDN spec with :gates and optional :proposal/:policy"]
   [nil "--card ID" "Existing kb card id"]
   [nil "--repo PATH" "Git repository root" :default "."]
   [nil "--base BRANCH" "Candidate base branch" :default "main"]
   [nil "--worktrees PATH" "Candidate worktree parent"]
   [nil "--ledger PATH" "DuckDB audit ledger path"]
   [nil "--inspect-run ID" "Print ordered audit events for one run"]
   [nil "--cleanup-run ID"
    "Idempotently remove a rejected/blocked run's candidate worktree"]
   [nil "--config PATH" "Lateralus Integrant config for the candidate agent"]
   [nil "--api-key-env NAME"
    "Read the LLM API key from this environment variable"]
   [nil "--agent NAME" "kb decision attribution" :default "lateralus"]
   ["-h" "--help" "Show help"]])

(def ^:private safe-read-opts
  {:eof nil
   :default (fn [tag value]
              (throw (ex-info "Unsupported tagged literal in evolution spec"
                              {:tag tag :value value})))})

(defn parse-args
  [args]
  (tools.cli/parse-opts args options))

(defn help-text
  [summary]
  (str "Usage: clojure -M:evolve --proposal spec.edn --card 011 [options]\n\n"
       "Runs one bounded candidate in an isolated Git worktree. When the EDN "
       "omits :proposal, a failing required gate becomes a self-diagnosed "
       "proposal. Passing "
       "candidates are committed locally and moved to kb Review; this command "
       "never pushes, merges, deploys, or completes a card.\n\n"
       summary))

(defn- read-spec
  [path]
  (let [spec (edn/read-string safe-read-opts (slurp path))]
    (when-not (schemas/valid? schemas/RunSpec spec)
      (throw (ex-info "Invalid evolution run specification"
                      {:explain (schemas/explain schemas/RunSpec spec)})))
    spec))

(defn- default-gates
  [selection]
  (let [{:keys [lint? fast-tests?]
         :or {lint? true fast-tests? true}} (or selection {})]
    (cond-> []
      lint?
      (conj {:id "lint"
    :kind :regression
    :argv ["clj-kondo" "--lint" "src" "test"]
    :timeout-ms 120000
    :network :deny
    :required? true})

      fast-tests?
      (conj {:id "fast-tests"
    :kind :regression
    :argv ["java" "--add-modules=jdk.incubator.vector"
           "--enable-native-access=ALL-UNNAMED"
           "-cp" "{{workspace-classpath}}"
           "clojure.main" "-m" "cognitect.test-runner" "-e" ":e2e"]
    :timeout-ms 900000
    :network :deny
    :required? true}))))

(defn- resolve-under
  [root path]
  (let [file (io/file path)]
    (.getCanonicalPath
     (if (.isAbsolute file) file (io/file root path)))))

(defn- operational-paths
  [{:keys [repo worktrees ledger]}]
  (let [repo (.getCanonicalPath (io/file repo))]
    {:repo repo
     :worktrees
     (resolve-under repo
                    (or worktrees
                        (.getPath (io/file ".lateralus" "worktrees"))))
     :ledger
     (resolve-under repo
                    (or ledger
                        (.getPath (io/file ".lateralus"
                                          "evolution.duckdb"))))}))

(defn inspect-run!
  [{:keys [inspect-run] :as options}]
  (let [{ledger-path :ledger} (operational-paths options)
        engine (duckdb/duckdb-store {:path ledger-path})]
    (try
      (let [events (audit-ledger/events engine inspect-run)]
        (when-not (seq events)
          (throw (ex-info "Evolution run was not found"
                          {:run-id inspect-run})))
        events)
      (finally
        (store/-close engine)))))

(defn cleanup-run!
  [{:keys [cleanup-run base] :as options}]
  (let [{:keys [repo worktrees] ledger-path :ledger}
        (operational-paths options)
        engine (duckdb/duckdb-store {:path ledger-path})]
    (try
      (let [events (audit-ledger/events engine cleanup-run)
            terminal (:phase (last events))
            candidate (some (fn [event]
                              (get-in event [:payload :candidate]))
                            (reverse events))]
        (when-not (contains? #{:rejected :blocked} terminal)
          (throw (ex-info
                  "Only rejected or blocked runs may be explicitly cleaned"
                  {:run-id cleanup-run :phase terminal})))
        (when-not candidate
          (throw (ex-info "Run has no recorded candidate"
                          {:run-id cleanup-run})))
        (let [runner (process/local-command-runner
                      {:allowed-programs #{"git"}
                       :allowed-roots [repo worktrees]})
              manager (worktree/git-workspace-manager
                       {:runner runner :repository-root repo
                        :worktree-parent worktrees
                        :base-branch base})]
          (proto/-discard-candidate! manager candidate)))
      (finally
        (store/-close engine)))))

(def ^:private verifier-programs #{"java" "clj-kondo"})

(defn validate-gates!
  [gates]
  (doseq [gate gates]
    (when-not (schemas/valid? schemas/GateSpec gate)
      (throw (ex-info "Invalid evolution gate specification"
                      {:gate gate})))
    (let [program (first (:argv gate))]
      (when-not (contains? verifier-programs program)
        (throw (ex-info "Evolution gate program is not allowlisted"
                        {:gate-id (:id gate)
                         :program program
                         :allowed-programs verifier-programs})))))
  gates)

(defn- assert-isolation-ready!
  [evaluator repository-root policy]
  (let [gate {:id "isolation-preflight"
              :kind :acceptance
              :argv ["java" "-version"]
              :timeout-ms 10000
              :network :deny
              :required? true}
        result (first (proto/-evaluate! evaluator repository-root
                                        [gate] policy))]
    (when-not (:passed? result)
      (throw (ex-info "Required verifier isolation is unavailable"
                      {:command (:command result)})))))

(defn run-evolution!
  [{:keys [proposal card repo base worktrees ledger config agent api-key-env]}]
  (when-not (and proposal card)
    (throw (ex-info "--proposal and --card are required" {})))
  (let [repo (.getCanonicalPath (io/file repo))
        worktrees (resolve-under
                   repo
                   (or worktrees
                       (.getPath (io/file ".lateralus" "worktrees"))))
        ledger-path (resolve-under
                     repo
                     (or ledger
                         (.getPath (io/file ".lateralus"
                                           "evolution.duckdb"))))
        spec (read-spec proposal)
        task-gates (vec (:gates spec))
        gates (vec (concat (default-gates (:default-gates spec)) task-gates))
        _ (validate-gates! gates)
        _ (when-not (some #(= :acceptance (:kind %)) task-gates)
            (throw (ex-info
                    "Proposal spec requires at least one :acceptance gate"
                    {})))
        _ (when-not (some #(and (= :regression (:kind %))
                                (:required? %))
                          gates)
            (throw (ex-info
                    "Evolution run requires at least one required regression gate"
                    {})))
        api-key (when api-key-env
                  (or (not-empty (System/getenv api-key-env))
                      (throw (ex-info "Evolution API key environment variable is unset"
                                      {:name api-key-env}))))
        _ (.mkdirs (.getParentFile (io/file ledger-path)))
        system-config
        (merge
         (agent.cli/build-system
          (cond-> {:workspace-root repo}
            api-key (assoc :api-key api-key)
            config (assoc :config config)))
         {:lateralus/evolution-store {:path ledger-path}
          :lateralus/evolution-command-runner
          {:allowed-programs #{"git" "kb"}
           :allowed-roots [repo worktrees]}
          :lateralus/evolution-verifier-runner
          {:allowed-programs #{"java" "clj-kondo"}
           :allowed-roots [repo worktrees]
           :classpath-root repo}
          :lateralus/evolution-board
          {:runner (ig/ref :lateralus/evolution-command-runner)
           :repository-root repo
           :agent agent}
          :lateralus/evolution-workspace
          {:runner (ig/ref :lateralus/evolution-command-runner)
           :repository-root repo
           :worktree-parent worktrees
           :base-branch base}
          :lateralus/evolution-ledger
          {:store (ig/ref :lateralus/evolution-store)}
          :lateralus/evolution-evaluator
          {:runner (ig/ref :lateralus/evolution-verifier-runner)}
          :lateralus/evolution-candidate
          {:agent-map (ig/ref :lateralus/agent)}})
        system (ig/init system-config)]
    (try
      (let [components
            {:board (:lateralus/evolution-board system)
             :workspace-manager (:lateralus/evolution-workspace system)
             :candidate-runner (:lateralus/evolution-candidate system)
             :evaluator (:lateralus/evolution-evaluator system)
             :ledger (:lateralus/evolution-ledger system)}
            normalized-policy
            (evolution.state/normalize-policy (:policy spec))
            _ (assert-isolation-ready! (:evaluator components)
                                       repo normalized-policy)
            proposal-data
            (or (:proposal spec)
                (let [card-context (proto/-context (:board components) card)
                      target (or (:worktree card-context) repo)
                      results (proto/-evaluate!
                               (:evaluator components) target gates
                               normalized-policy)]
                  (discovery/proposal-from-failures gates results)))
            _ (when-not proposal-data
                (throw (ex-info
                        "No failing required gate found for self-diagnosis"
                        {:card-id card})))
            result (supervisor/evolve!
                    components
                    {:repository-root repo
                     :card-id card
                     :proposal proposal-data
                     :policy (:policy spec)
                     :gates gates})]
        result)
      (finally
        (ig/halt! system)))))

(defn -main
  [& args]
  (let [{:keys [options errors summary]} (parse-args args)]
    (cond
      (:help options)
      (println (help-text summary))

      (seq errors)
      (do
        (binding [*out* *err*]
          (println (str/join "\n" errors))
          (println (help-text summary)))
        (System/exit 1))

      :else
      (try
        (let [operation (cond
                          (:inspect-run options) :inspect
                          (:cleanup-run options) :cleanup
                          :else :evolve)
              result (case operation
                       :inspect (inspect-run! options)
                       :cleanup (cleanup-run! options)
                       (run-evolution! options))]
          (prn result)
          (when (and (= :evolve operation)
                     (not= :handoff (:phase result)))
            (System/exit 2)))
        (catch Throwable t
          (binding [*out* *err*]
            (println (or (ex-message t) (.getName (class t)))))
          (System/exit 1))))))
