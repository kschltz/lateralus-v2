(ns kschltz.agent.plugins.workbench
  "Partial interceptor plugin for the CHAT | Portal workbench pack.

   Makes Portal the obvious agent surface for data + visualization:
     :guard  — seed `:agent/workbench` and merge `portal_*` tools
     :enrich — append Portal policy to `:agent/system-append` so
               compose-context puts it in the system message every turn"
  (:require [kschltz.agent.workbench.guidance :as guidance]
            [kschltz.agent.workbench.protocol :as wb]))
(defn- seed-interceptor
  [workbench]
  {:name ::seed-workbench
   :slot :guard
   :enter (fn [ctx]
            (cond-> (assoc ctx :agent/workbench workbench)
              workbench
              (update :agent/tool-registry
                      (fn [reg]
                        (merge (or reg {}) (wb/tools workbench))))))})

(defn- guidance-interceptor
  "`:enrich` interceptor: advertise Portal as the only viz channel."
  []
  {:name ::portal-guidance
   :slot :enrich
   :enter (fn [ctx]
            (let [prior (:agent/system-append ctx)
                  block guidance/portal-system-guidance
                  merged (cond
                           (string? prior)
                           (str prior "\n\n" block)

                           (sequential? prior)
                           (conj (vec prior) block)

                           :else block)]
              (assoc ctx :agent/system-append merged)))})

(defn workbench-plugin
  "Build a partial plugin around a live `Workbench` instance.

   When `workbench` is nil (disabled), returns an empty plugin vector
   so the chain is unchanged."
  [workbench]
  (with-meta
    (if workbench
      [(seed-interceptor workbench)
       (guidance-interceptor)]
      [])
    {:plugin/name :workbench
     :plugin/rebuild (fn [] (workbench-plugin workbench))}))
