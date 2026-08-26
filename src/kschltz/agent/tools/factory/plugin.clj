(ns kschltz.agent.tools.factory.plugin
  "Partial plugin: seed the factory session and run runtime interceptors."
  (:require [kschltz.agent.tools.factory.protocol :as proto]))

(def system-guidance
  "TOOL AUTHORING: tool_define is registered. Call it to add a callable tool, then invoke the new name this exchange. Do not clojure_eval a substitute.")

(def ^:private dispatch-slots
  "Slots that can host a runtime-defined interceptor without a rebuild."
  [:guard :enrich :observe :finalize])

(defn- seed-interceptor
  [session]
  {:name ::seed-factory
   :slot :guard
   :enter (fn [ctx]
            (when (proto/runtime-tool-store? session)
              (proto/-rehydrate! session
                                 (get-in ctx [:agent/state :agent/runtime-tools])))
            (assoc ctx :agent/factory-session session))})

(defn- guidance-interceptor
  []
  {:name ::factory-guidance
   :slot :enrich
   :enter (fn [ctx]
            (update ctx :agent/system-append
                    (fn [prior]
                      (cond
                        (string? prior) (str prior "\n\n" system-guidance)
                        (sequential? prior) (conj (vec prior) system-guidance)
                        :else system-guidance))))})

(defn- run-enter
  [session slot]
  (fn [ctx]
    (if-not (proto/runtime-tool-store? session)
      ctx
      (reduce (fn [c ix]
                (if-let [e (:enter ix)]
                  (e c)
                  c))
              ctx
              (proto/-interceptors session slot)))))

(defn- run-leave
  [session slot]
  (fn [ctx]
    (if-not (proto/runtime-tool-store? session)
      ctx
      (reduce (fn [c ix]
                (if-let [l (:leave ix)]
                  (l c)
                  c))
              ctx
              (reverse (proto/-interceptors session slot))))))

(defn- dispatch-interceptor
  [session slot]
  {:name (keyword "kschltz.agent.tools.factory.plugin" (name slot))
   :slot slot
   :enter (run-enter session slot)
   :leave (run-leave session slot)})

(defn factory-plugin
  "Partial plugin around a live `RuntimeToolStore`.

   Seeds `:agent/factory-session` and dispatches any runtime-defined
   interceptors for :guard / :enrich / :observe / :finalize."
  [session]
  (with-meta
    (if (proto/runtime-tool-store? session)
      (into [(seed-interceptor session) (guidance-interceptor)]
            (map #(dispatch-interceptor session %))
            dispatch-slots)
      [])
    {:plugin/name :factory
     :plugin/rebuild (fn [] (factory-plugin session))}))
