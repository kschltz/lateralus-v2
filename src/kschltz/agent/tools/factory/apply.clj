(ns kschltz.agent.tools.factory.apply
  "Reconcile factory transitions against a live RuntimeToolStore."
  (:require [kschltz.agent.tools.factory.protocol :as proto]
            [kschltz.agent.transitions :as tr]))

(def factory-ops
  #{:register-runtime-tool :forget-runtime-tool :promote-runtime-tool})

(defn factory-op?
  [op]
  (contains? factory-ops (:op op)))

(defn- phase-of
  [^Throwable t]
  (let [p (:phase (ex-data t))]
    (if (keyword? p) (name p) "tool")))

(defn reserved-names
  "Non-factory tool names currently on the context registry."
  [ctx store]
  (let [all (set (keys (or (:agent/tool-registry ctx) {})))
        factory (set (keys (proto/-registry store)))]
    (into #{} (remove factory) all)))

(defn reconcile-op
  "Run live store I/O for one factory transition. Returns
   `{:ok true :status m}` or `{:ok false :error :phase :class}`."
  [store ctx op]
  (try
    (when (and (contains? #{:register-runtime-tool
                            :forget-runtime-tool
                            :promote-runtime-tool}
                          (:op op))
               (not (proto/-dynamic-enabled? store)))
      (throw (ex-info
              "Dynamic tool factory is disabled; set :dynamic {:enabled? true} on :lateralus/factory-session"
              {:phase :disabled})))
    (let [status
          (case (:op op)
            :register-runtime-tool
            (proto/-define! store (:spec op) {:reserved-names (reserved-names ctx store)})
            :forget-runtime-tool
            (proto/-forget! store (:tool-name op))
            :promote-runtime-tool
            (proto/-promote! store (:tool-name op)
                             {:as-plugin (:as-plugin op)
                              :target (:target op)
                              :workspace-root (:workspace-root op)})
            nil)]
      {:ok true :status status})
    (catch Throwable t
      {:ok false
       :error (or (ex-message t) (.getName (class t)))
       :phase (phase-of t)
       :class (.getName (class t))})))

(defn- same-factory-op?
  [a b]
  (and (map? a) (map? b)
       (= (:op a) (:op b))
       (or (and (= :register-runtime-tool (:op a))
                (= (get-in a [:spec :name]) (get-in b [:spec :name])))
           (= (str (:tool-name a)) (str (:tool-name b))))))

(defn- tool-name-for-op
  [op]
  (case (:op op)
    :register-runtime-tool "tool_define"
    :forget-runtime-tool "tool_forget"
    :promote-runtime-tool "tool_promote"
    "tool_define"))

(defn rewrite-entry
  [entry op outcome]
  (let [parsed (tr/parse-tool-result (:result entry))
        status (:status outcome)]
    (if (:ok outcome)
      (assoc entry
             :result
             (tr/encode-result
              (cond-> (or parsed {})
                true (assoc :ok true
                            :pending "same-exchange"
                            :transition (tr/redact-transition op))
                (some? (:tools status)) (assoc :tools (:tools status))
                (some? (:tool-name status)) (assoc :tool-name (:tool-name status))
                (some? (:paths status)) (assoc :paths (:paths status)
                                               :ns (:ns status)
                                               :catalog (:catalog status)
                                               :target (:target status))
                (some? (:removed status)) (assoc :removed (:removed status)))))
      (assoc entry
             :result
             (tr/encode-result
              {:ok false
               :tool (or (:tool parsed) (tool-name-for-op op))
               :error (:error outcome)
               :phase (:phase outcome)
               :class (:class outcome)})))))

(defn rewrite-results
  [results outcomes]
  (reduce (fn [entries {:keys [op outcome]}]
            (mapv (fn [entry]
                    (let [et (some-> entry :result tr/parse-tool-result
                                     :transition)]
                      (if (same-factory-op? et op)
                        (rewrite-entry entry op outcome)
                        entry)))
                  entries))
          (vec (or results []))
          outcomes))
