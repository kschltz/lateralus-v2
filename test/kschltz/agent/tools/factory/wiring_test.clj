(ns kschltz.agent.tools.factory.wiring-test
  (:require [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [kschltz.agent.system :as system]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.factory.protocol :as proto]
            [kschltz.agent.tools.factory.wiring :as wiring]))

(deftest default-keys-cover-session-tools-plugin
  (is (contains? wiring/default-keys :lateralus/factory-session))
  (is (contains? wiring/default-keys :lateralus/factory-tools))
  (is (contains? wiring/default-keys :lateralus/factory-plugin)))

(deftest default-system-exposes-factory-control-tools
  (let [sys (ig/init system/default-config
                     [:lateralus/factory-session
                      :lateralus/factory-tools
                      :lateralus/tool-registry
                      :lateralus/factory-plugin])]
    (try
      (is (proto/runtime-tool-store? (:lateralus/factory-session sys)))
      (is (contains? (:lateralus/tool-registry sys) "tool_define"))
      (is (tool/tool? (get (:lateralus/tool-registry sys) "tool_promote")))
      (is (vector? (:lateralus/factory-plugin sys)))
      (finally
        (ig/halt! sys)))))
