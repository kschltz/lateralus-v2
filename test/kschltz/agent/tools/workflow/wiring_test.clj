(ns kschltz.agent.tools.workflow.wiring-test
  (:require [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [kschltz.agent.system :as system]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.workflow.protocol :as proto]
            [kschltz.agent.tools.workflow.wiring :as wiring]))

(deftest default-keys-cover-workflow-tools
  (is (contains? wiring/default-keys :lateralus/workflow-tools)))

(deftest default-system-exposes-workflow-tools
  (let [sys (ig/init system/default-config
                     [:lateralus/workflow-tools
                      :lateralus/tool-registry])]
    (try
      (is (tool/tool? (get (:lateralus/workflow-tools sys)
                           "workflow_run")))
      (is (contains? (:lateralus/tool-registry sys) "workflow_register_action"))
      (is (contains? (:lateralus/tool-registry sys) "workflow_seed"))
      (is (contains? (:lateralus/tool-registry sys) "workflow_run"))
      (is (proto/workflow-engine?
           (-> (:lateralus/workflow-tools sys) meta :workflow/engine)))
      (finally
        (ig/halt! sys)))))
