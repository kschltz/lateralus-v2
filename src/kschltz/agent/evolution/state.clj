(ns kschltz.agent.evolution.state
  "Pure state and policy decisions for evolution runs."
  (:require [clojure.string :as str]
            [kschltz.agent.evolution.schemas :as schemas]
            [malli.core :as m]
            [malli.error :as me]))

(def default-policy
  {:max-wall-ms 1800000
   :max-candidates 2
   :max-agent-turns 8
   :max-tool-calls 40
   :max-token-usage 100000
   :max-changed-files 30
   :max-output-bytes 1048576
   :allowed-paths ["src/" "docs/" "resources/" "README.md" "CHANGELOG.md"]
   :protected-paths [".git/" ".kanban/" ".lateralus/" "test/"
                     "deps.edn" "build.clj" "AGENT_INSTRUCTIONS.md"
                     "src/kschltz/agent/evolution/"
                     "resources/lateralus/config.edn"
                     "resources/lateralus/evolution.edn"]
   :require-network-isolation? true
   :require-workspace-isolation? true})

(def ^:private next-phases
  {:observe #{:propose :blocked :rejected}
   :propose #{:baseline :blocked :rejected}
   :baseline #{:isolate :blocked :rejected}
   :isolate #{:implement :blocked :rejected}
   :implement #{:verify :blocked :rejected}
   :verify #{:handoff :blocked :rejected}
   :handoff #{}
   :blocked #{}
   :rejected #{}})

(defn normalize-policy
  [policy]
  (let [merged (merge default-policy (or policy {}))]
    (when-not (schemas/valid? schemas/Policy merged)
      (throw (ex-info "Invalid evolution policy"
                      {:policy merged
                       :explain (some-> (schemas/explain schemas/Policy merged)
                                        me/humanize)})))
    merged))

(defn transition
  [state phase now payload]
  (when-not (contains? (get next-phases (:phase state) #{}) phase)
    (throw (ex-info "Illegal evolution phase transition"
                    {:from (:phase state) :to phase})))
  (merge state payload {:phase phase :updated-at now}))

(defn- under-prefix?
  [path prefix]
  (if (str/ends-with? prefix "/")
    (str/starts-with? path prefix)
    (or (= path prefix)
        (str/starts-with? path (str prefix "/")))))

(defn path-violations
  [paths policy]
  (let [protected (:protected-paths policy)
        allowed (:allowed-paths policy)]
    (cond-> []
      (empty? paths)
      (conj {:error :no-changes})

      (> (count paths) (:max-changed-files policy))
      (conj {:error :changed-file-budget
             :actual (count paths)
             :limit (:max-changed-files policy)})

      (some #(or (str/starts-with? % "/")
                 (str/includes? % "../")
                 (= % ".."))
            paths)
      (conj {:error :unsafe-path})

      (seq (remove (fn [path] (some #(under-prefix? path %) allowed)) paths))
      (conj {:error :outside-allowed-paths
             :paths (vec (remove (fn [path]
                                   (some #(under-prefix? path %) allowed))
                                 paths))})

      (seq (filter (fn [path] (some #(under-prefix? path %) protected)) paths))
      (conj {:error :protected-path
             :paths (vec (filter (fn [path]
                                   (some #(under-prefix? path %) protected))
                                 paths))}))))

(defn budget-violations
  [started-at now implementation policy]
  (cond-> []
    (> (- now started-at) (:max-wall-ms policy))
    (conj {:error :wall-time-budget})

    (> (:turns implementation) (:max-agent-turns policy))
    (conj {:error :agent-turn-budget})

    (> (:tool-calls implementation) (:max-tool-calls policy))
    (conj {:error :tool-call-budget})

    (> (:token-usage implementation) (:max-token-usage policy))
    (conj {:error :token-budget})))

(defn gate-decision
  [baseline verification]
  (let [baseline-by-id (into {} (map (juxt :id identity)) baseline)
        decisions
        (mapv
         (fn [gate]
           (let [before (get baseline-by-id (:id gate))
                 accepted?
                 (case (:kind gate)
                   :acceptance (:passed? gate)
                   :regression (or (:passed? gate)
                                   (and before (not (:passed? before)))))]
             {:id (:id gate)
              :kind (:kind gate)
              :required? (:required? gate)
              :accepted? accepted?
              :baseline-passed? (boolean (:passed? before))
              :candidate-passed? (:passed? gate)}))
         verification)]
    {:accepted? (every? #(or (not (:required? %)) (:accepted? %)) decisions)
     :gates decisions}))

(m/=> normalize-policy [:=> [:cat [:maybe :map]] schemas/Policy])
(m/=> transition
      [:=> [:cat schemas/RunState schemas/Phase [:int {:min 0}] :map]
       schemas/RunState])
(m/=> path-violations
      [:=> [:cat [:vector :string] schemas/Policy] [:vector :map]])
(m/=> budget-violations
      [:=> [:cat [:int {:min 0}] [:int {:min 0}]
            schemas/ImplementationResult schemas/Policy]
       [:vector :map]])
(m/=> gate-decision
      [:=> [:cat [:vector schemas/GateResult] [:vector schemas/GateResult]]
       [:map [:accepted? :boolean] [:gates [:vector :map]]]])
