(ns kschltz.agent.runtime-reload
  "Outer-runtime executor for deferred interceptor-chain reload.

   After a reload, run a health probe (guard+compose + JSON-encode the
   would-be LLM request). If that fails, restore snapshotted source and
   the previous chain so a bad edit cannot leave Lateralus unusable."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.tool :as tool]))

(def ^:private restart-required-namespaces
  #{"kschltz.agent.runtime"
    "kschltz.agent.chain"
    "kschltz.agent.system"
    "kschltz.agent.tool"
    "kschltz.agent.transitions"})

(def ^:private probe-slots
  #{:guard :compose})

(defn- record-status!
  [runtime status]
  (swap! (:state runtime)
         (fn [state]
           (-> state
               (dissoc :agent/runtime-reload)
               (assoc :agent/runtime-reload-status status)))))

(defn ns-source-file
  "src/ path for a `kschltz.agent.*` namespace, or nil."
  [ns-name]
  (let [rel (-> (str ns-name)
                (str/replace "-" "_")
                (str/replace "." java.io.File/separator)
                (str ".clj"))
        f (io/file "src" rel)]
    (when (.isFile f) f)))

(defn snapshot-sources
  [namespaces]
  (into {}
        (keep (fn [ns-name]
                (when-let [f (ns-source-file ns-name)]
                  [(.getPath f) (slurp f)])))
        namespaces))

(defn restore-sources!
  [snapshot]
  (doseq [[path content] snapshot]
    (spit path content)))

(defn- reload-namespaces!
  [namespaces]
  (doseq [ns-name namespaces]
    (require (symbol ns-name) :reload)))

(defn- run-probe-enters
  [chain ctx]
  (reduce (fn [c ix]
            (if (and (contains? probe-slots (:slot ix))
                     (ifn? (:enter ix)))
              ((:enter ix) c)
              c))
          ctx
          chain))

(defn probe-operational
  "Return nil when the live chain can compose a JSON-encodable LLM
   request. Otherwise a short error string. Does not call the LLM."
  [runtime]
  (try
    (let [chain @(:chain runtime)
          ctx {:exchange/user-text "health-probe"
               :exchange/session-id "health-probe"
               :agent/state (or @(:state runtime) {})
               :agent/agent-map (:agent-map runtime)
               :agent/loop-opts (or (get-in runtime [:agent-map :agent/loop-opts]) {})
               :llm/client (get-in runtime [:agent-map :agent/llm-client])
               :llm/request {:model "health-probe"
                             :messages [{:role "user" :content "ping"}]}
               :memory/recall []}
          after (run-probe-enters chain ctx)
          req (or (:llm/request after) {})]
      (when-not (vector? chain)
        (throw (ex-info "chain is not a vector" {})))
      (json/generate-string
       (tool/json-safe
        {:model (or (:model req) "health-probe")
         :messages (or (:messages req) [{:role "user" :content "ping"}])
         :tools (or (:tools req) [])}))
      nil)
    (catch Throwable t
      (or (ex-message t) (.getName (class t))))))

(defn- rollback!
  [runtime {:keys [snapshot old-chain rebuild namespaces]}]
  (when (seq snapshot)
    (restore-sources! snapshot)
    (try (reload-namespaces! namespaces)
         (catch Throwable _)))
  (let [restored (if (seq snapshot)
                   (or (when (fn? rebuild)
                         (try (rebuild) (catch Throwable _ nil)))
                       old-chain)
                   old-chain)]
    (when (vector? restored)
      (reset! (:chain runtime) restored))))

(defn apply!
  "Consume a deferred reload request after the current exchange finishes.

   `runtime` supplies outer-loop-owned `:state` and `:chain` atoms plus an
   immutable `:agent-map` containing the Integrant-built rebuild closure."
  [runtime request]
  (let [namespaces (vec (distinct (:namespaces request)))
        restart-required (vec (filter restart-required-namespaces namespaces))
        rebuild (:agent/rebuild-chain (:agent-map runtime))
        old-chain @(:chain runtime)
        snapshot (snapshot-sources namespaces)]
    (try
      (cond
        (seq restart-required)
        (record-status! runtime
                        {:ok false
                         :status :restart-required
                         :namespaces restart-required})

        (not (fn? rebuild))
        (record-status! runtime
                        {:ok false
                         :status :unavailable
                         :namespaces namespaces})

        :else
        (do
          (reload-namespaces! namespaces)
          (let [new-chain (rebuild)]
            (when-not (vector? new-chain)
              (throw (ex-info "Runtime chain rebuild did not return a vector"
                              {:namespaces namespaces})))
            (reset! (:chain runtime) new-chain)
            (if-let [err (probe-operational runtime)]
              (do
                (rollback! runtime {:snapshot snapshot
                                    :old-chain old-chain
                                    :rebuild rebuild
                                    :namespaces namespaces})
                (record-status! runtime
                                {:ok false
                                 :status :rolled-back
                                 :namespaces namespaces
                                 :error err
                                 :restored-files (vec (sort (keys snapshot)))}))
              (let [revision (inc
                              (get-in @(:state runtime)
                                      [:agent/runtime-reload-status :revision]
                                      0))]
                (record-status! runtime
                                {:ok true
                                 :status :reloaded
                                 :namespaces namespaces
                                 :revision revision
                                 :interceptor-count (count new-chain)}))))))
      (catch Throwable t
        (rollback! runtime {:snapshot snapshot
                            :old-chain old-chain
                            :rebuild rebuild
                            :namespaces namespaces})
        (record-status! runtime
                        {:ok false
                         :status :error
                         :namespaces namespaces
                         :error (or (ex-message t)
                                    (.getName (class t)))
                         :rolled-back? true})))))

(defn notice-interceptor
  "`:enrich` interceptor: tell the model when the last reload was
   rolled back or failed so it does not keep editing a dead runtime."
  []
  {:name ::reload-notice
   :slot :enrich
   :enter (fn [ctx]
            (let [st (get-in ctx [:agent/state :agent/runtime-reload-status])
                  msg (case (:status st)
                        :rolled-back
                        (str "RUNTIME SAFETY: last reload was rolled back because "
                             "Lateralus failed a health probe (" (or (:error st) "unknown")
                             "). Source and the interceptor chain were restored. "
                             "Tell the human; do not reload the same edit.")
                        :error
                        (str "RUNTIME SAFETY: last reload failed (" (or (:error st) "unknown")
                             "). The previous chain was restored. Tell the human.")
                        nil)]
              (if msg
                (update ctx :agent/system-append
                        (fn [prior]
                          (cond
                            (string? prior) (str prior "\n\n" msg)
                            (sequential? prior) (conj (vec prior) msg)
                            :else msg)))
                ctx)))})
