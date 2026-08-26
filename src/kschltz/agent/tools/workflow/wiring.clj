(ns kschltz.agent.tools.workflow.wiring
  "Integrant keys for the workflow tool suite."
  (:require [integrant.core :as ig]
            [kschltz.agent.tools.workflow.session :as session]
            [kschltz.agent.tools.workflow.tools :as tools]))

(defmethod ig/init-key :lateralus/workflow-tools [_ opts]
  (let [eng (or (:engine opts) (session/workflow-session opts))
        reg (tools/workflow-tools-registry eng)]
    (with-meta reg
      {:registry/rebuild #(tools/workflow-tools-registry eng)
       :workflow/engine eng})))

(def default-keys
  "Keys to merge into `system/default-config`."
  {:lateralus/workflow-tools {}})
