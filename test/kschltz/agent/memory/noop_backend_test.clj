(ns kschltz.agent.memory.noop-backend-test
  "Tests for the noop MemoryBackend implementation.

   The noop backend is the MVP default. It satisfies the
   MemoryBackend protocol without persisting anything, so recall
   always returns []."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.memory.noop-backend :as noop]
            [kschltz.agent.memory.protocol :as mem]))

(deftest noop-backend-stores-nothing
  (testing "storing then recalling still returns []"
    (let [b (noop/backend)]
      (mem/-store-message b "session-1" {:role "user" :content "hi"})
      (mem/-store-message b "session-1" {:role "assistant" :content "hello"})
      (is (= [] (mem/-recall-hybrid b "session-1" {:top-y 2 :last-n 5}))
          "noop recall is always empty")
      (is (= [] (mem/-recall-hybrid b "unknown-session" {:top-y 2 :last-n 5}))
          "noop recall is empty even for unknown sessions"))))

(deftest noop-backend-close-is-safe
  (testing "closing the noop backend is a no-op and safe to call repeatedly"
    (let [b (noop/backend)]
      (is (nil? (mem/-close b)))
      (is (nil? (mem/-close b))))))
