(ns kschltz.agent.memory.kg-bm25-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.memory.kg-bm25 :as kg]
            [kschltz.agent.memory.protocol :as mem]))

(deftest backend-satisfies-memory-backend
  (is (satisfies? mem/MemoryBackend (kg/backend {:store {:backend :memory}}))))

(deftest backend-stores-and-recalls-recent
  (let [b (kg/backend {:store {:backend :memory}})]
    (mem/-store-message b "s1" {:role "user" :content "hello"
                                    :msg-id "u1" :timestamp 100})
    (is (= [{:role "user" :content "hello" :msg-id "u1" :timestamp 100}]
           (mem/-recall-hybrid b "s1" {:top-y 0 :last-n 5})))))

(deftest backend-closes
  (let [b (kg/backend {:store {:backend :memory}})]
    (mem/-store-message b "s1" {:role "user" :content "x"
                                    :msg-id "u1" :timestamp 1})
    (is (nil? (mem/-close b)))
    (is (= [] (mem/-recall-hybrid b "s1" {:top-y 3 :last-n 5})))))
