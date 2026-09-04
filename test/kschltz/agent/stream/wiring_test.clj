(ns kschltz.agent.stream.wiring-test
  (:require [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [kschltz.agent.stream.plugin :as stream.plugin]
            [kschltz.agent.stream.protocol :as proto]
            [kschltz.agent.stream.wiring :as wiring]
            [kschltz.agent.store.memory :as store.memory]
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

(deftest empty-config-is-memory-bus
  (let [bus (ig/init-key :lateralus/stream-bus {})]
    (is (proto/stream-bus? bus))
    (is (nil? (:engine bus)))))

(deftest store-impl-requires-engine
  (is (thrown? clojure.lang.ExceptionInfo
               (ig/init-key :lateralus/stream-bus {:impl :store}))))

(deftest store-impl-wraps-engine
  (let [engine (store.memory/memory-store)
        bus (ig/init-key :lateralus/stream-bus {:impl :store :store engine})]
    (is (proto/stream-bus? bus))
    (is (identical? engine (:engine bus)))))
