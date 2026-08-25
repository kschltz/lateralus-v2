(ns kschltz.agent.runtime-reload
  "Outer-runtime executor for deferred interceptor-chain reload requests.")

(def ^:private restart-required-namespaces
  #{"kschltz.agent.runtime"
    "kschltz.agent.chain"
    "kschltz.agent.system"
    "kschltz.agent.tool"
    "kschltz.agent.transitions"})

(defn- record-status!
  [runtime status]
  (swap! (:state runtime)
         (fn [state]
           (-> state
               (dissoc :agent/runtime-reload)
               (assoc :agent/runtime-reload-status status)))))

(defn apply!
  "Consume a deferred reload request after the current exchange finishes.

   `runtime` supplies outer-loop-owned `:state` and `:chain` atoms plus an
   immutable `:agent-map` containing the Integrant-built rebuild closure."
  [runtime request]
  (let [namespaces (vec (distinct (:namespaces request)))
        restart-required (vec (filter restart-required-namespaces namespaces))
        rebuild (:agent/rebuild-chain (:agent-map runtime))]
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
          (doseq [ns-name namespaces]
            (require (symbol ns-name) :reload))
          (let [new-chain (rebuild)]
            (when-not (vector? new-chain)
              (throw (ex-info "Runtime chain rebuild did not return a vector"
                              {:namespaces namespaces})))
            (reset! (:chain runtime) new-chain)
            (let [revision (inc
                            (get-in @(:state runtime)
                                    [:agent/runtime-reload-status :revision]
                                    0))]
              (record-status! runtime
                              {:ok true
                               :status :reloaded
                               :namespaces namespaces
                               :revision revision
                               :interceptor-count (count new-chain)})))))
      (catch Throwable t
        (record-status! runtime
                        {:ok false
                         :status :error
                         :namespaces namespaces
                         :error (or (ex-message t)
                                    (.getName (class t)))})))))
