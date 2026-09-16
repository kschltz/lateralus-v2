(ns kschltz.agent.evolution.ledger-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.evolution.ledger :as ledger]
            [kschltz.agent.evolution.protocol :as proto]
            [kschltz.agent.store.memory :as memory]))

(deftest events-roundtrip-in-order
  (let [store (memory/memory-store)
        audit (ledger/store-ledger store)
        event (fn [id ts]
                {:id id :run-id "run-1" :type :phase-entered
                 :phase :observe :ts ts :payload {:n ts}})]
    (proto/-append-event! audit (event "b" 2))
    (proto/-append-event! audit (event "a" 1))
    (is (= ["a" "b"] (mapv :id (proto/-events audit "run-1"))))
    (is (= [{:n 1} {:n 2}]
           (mapv :payload (proto/-events audit "run-1"))))))
