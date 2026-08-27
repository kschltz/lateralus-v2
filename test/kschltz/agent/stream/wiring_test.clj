(ns kschltz.agent.stream.wiring-test
  (:require [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [kschltz.agent.stream.plugin :as stream.plugin]
            [kschltz.agent.stream.protocol :as proto]
            [kschltz.agent.stream.wiring :as wiring]
            [kschltz.agent.system :as system]))

(deftest default-keys-include-bus-and-plugin
  (is (contains? wiring/default-keys :lateralus/stream-bus))
  (is (contains? wiring/default-keys :lateralus/stream-plugin)))

(deftest default-system-wires-stream-plugin
  (let [sys (ig/init system/default-config
                     [:lateralus/stream-bus
                      :lateralus/stream-plugin
                      :lateralus/plugins])]
    (try
      (is (proto/stream-bus? (:lateralus/stream-bus sys)))
      (is (some #(= ::stream.plugin/seed-stream (:name %))
                (flatten (:lateralus/plugins sys))))
      (finally
        (ig/halt! sys)))))
