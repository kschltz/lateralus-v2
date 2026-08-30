(ns kschltz.agent.tools.factory.plugin
  "Partial plugin: seed the factory session and run runtime interceptors."
  (:require [clojure.string :as str]
            [kschltz.agent.tools.factory.protocol :as proto]))

(def system-guidance
  "TOOL AUTHORING: tool_define is registered. Call it to add a callable tool, then use tool_test with real arguments and the exact expected output. A passing test is required before tool_promote and is invalidated by redefinition. Do not clojure_eval a substitute.")

(def ^:private dispatch-slots
  "Slots that can host a runtime-defined interceptor without a rebuild."
  [:guard :enrich :observe :finalize])

(defn- seed-interceptor
  [session]
  {:name ::seed-factory
   :slot :guard
   :enter (fn [ctx]
            (when (proto/runtime-tool-store? session)
              (let [{:keys [errors]} (proto/-rehydrate!
                                      session
                                      (get-in ctx [:agent/state
                                                   :agent/runtime-tools]))
                    notice
                    (when (seq errors)
                      (str "RUNTIME TOOL COMPILE FAILURES — these tools were"
                           " defined but never registered, so calls to them\n"
                           " return 'not available'. Re-define with the fix:\n"
                           (str/join
                            "\n"
                            (map (fn [{:keys [name error]}]
                                   (str "- " name ": " error))
                                 errors))))]
                (cond-> (assoc ctx :agent/factory-session session)
                  notice
                  (update :agent/system-append
                          (fn [prior]
                            (cond
                              (string? prior) (str prior "\n\n" notice)
                              (sequential? prior) (conj (vec prior) notice)
                              :else notice)))))))})

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
