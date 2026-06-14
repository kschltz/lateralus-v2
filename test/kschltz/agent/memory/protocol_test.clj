(ns kschltz.agent.memory.protocol-test
  "Tests for the MemoryBackend protocol contract.

   The protocol is the storage boundary. MVP only ships a noop
   backend; this ns verifies the contract shape so a future real
   backend can be tested against the same assertions."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.memory.noop-backend :as noop]
            [kschltz.agent.memory.protocol :as mem]))

(deftest noop-backend-satisfies-memory-backend
  (testing "the noop backend satisfies the MemoryBackend protocol"
    (let [b (noop/backend)]
      (is (satisfies? mem/MemoryBackend b))
      (is (nil? (mem/-store-message b "s" {:role "user" :content "hi"})))
      (is (= [] (mem/-recall-hybrid b "s" {:top-y 3 :last-n 5})))
      (is (nil? (mem/-close b))))))

(deftest store-message-returns-backend-or-nil
  (testing "-store-message may return the backend or nil (implementation detail)"
    (let [b (noop/backend)]
      ;; The noop backend returns nil. A real backend might return
      ;; itself for threading. Both are acceptable per the docstring.
      (is (contains? #{nil b} (mem/-store-message b "s" {}))))))
