(ns kschltz.agent.evolution.cli
  "One-shot local entry point for bounded, worktree-isolated evolution."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as tools.cli]
            [integrant.core :as ig]
            [kschltz.agent.cli :as agent.cli]
            [kschltz.agent.evolution.discovery :as discovery]
            [kschltz.agent.evolution.protocol :as proto]
            [kschltz.agent.evolution.schemas :as schemas]
            [kschltz.agent.evolution.state :as evolution.state]
            [kschltz.agent.evolution.supervisor :as supervisor]
            [kschltz.agent.evolution.wiring]))

(def ^:private options
  [[nil "--proposal PATH" "EDN spec with :gates and optional :proposal/:policy"]
   [nil "--card ID" "Existing kb card id"]
   [nil "--repo PATH" "Git repository root" :default "."]
   [nil "--base BRANCH" "Candidate base branch" :default "main"]
   [nil "--worktrees PATH" "Candidate worktree parent"]
   [nil "--ledger PATH" "DuckDB audit ledger path"]
   [nil "--config PATH" "Lateralus Integrant config for the candidate agent"]
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
  (edn/read-string safe-read-opts (slurp path)))

(defn- default-gates
  []
  [{:id "lint"
    :kind :regression
    :argv ["clj-kondo" "--lint" "src" "test"]
    :timeout-ms 120000
    :network :deny
    :required? true}
   {:id "fast-tests"
    :kind :regression
    :argv ["java" "--add-modules=jdk.incubator.vector"
           "--enable-native-access=ALL-UNNAMED"
           "-cp" "{{workspace-classpath}}"
           "clojure.main" "-m" "cognitect.test-runner" "-e" ":e2e"]
    :timeout-ms 900000
    :network :deny
    :required? true}])

(defn- resolve-under
  [root path]
  (let [file (io/file path)]
    (.getCanonicalPath
     (if (.isAbsolute file) file (io/file root path)))))

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

(defn run-evolution!
  [{:keys [proposal card repo base worktrees ledger config agent]}]
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
        gates (vec (concat (default-gates) task-gates))
        _ (validate-gates! gates)
        _ (when-not (some #(= :acceptance (:kind %)) task-gates)
            (throw (ex-info
                    "Proposal spec requires at least one :acceptance gate"
                    {})))
        _ (.mkdirs (.getParentFile (io/file ledger-path)))
        system-config
        (merge
         (agent.cli/build-system
          (cond-> {:workspace-root repo}
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
            proposal-data
            (or (:proposal spec)
                (let [card-context (proto/-context (:board components) card)
                      target (or (:worktree card-context) repo)
                      results (proto/-evaluate!
                               (:evaluator components) target gates
                               (evolution.state/normalize-policy
                                (:policy spec)))]
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
        (let [result (run-evolution! options)]
          (prn result)
          (when-not (= :handoff (:phase result))
            (System/exit 2)))
        (catch Throwable t
          (binding [*out* *err*]
            (println (or (ex-message t) (.getName (class t)))))
          (System/exit 1))))))
