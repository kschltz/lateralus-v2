(ns kschltz.agent.interceptors.schema-test
  "Tests for the Ctx/Interceptor schemas and the built-in validator."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.interceptors.schema :as schema]
            [malli.core :as m]))

(deftest interceptor-schema-validates-shapes
  (testing "valid interceptor with all stages"
    (is (not (m/explain schema/Interceptor
                         {:name :a
                          :enter (fn [c] c)
                          :leave (fn [c] c)
                          :error (fn [c _] c)}))))
  (testing "valid interceptor with only :name"
    (is (not (m/explain schema/Interceptor {:name :a}))))
  (testing "missing :name fails"
    (is (m/explain schema/Interceptor {:enter (fn [c] c)})))
  (testing "non-fn :enter fails"
    (is (m/explain schema/Interceptor {:name :a :enter "not a fn"})))
  (testing "non-keyword :name fails"
    (is (m/explain schema/Interceptor {:name "a"}))))

(deftest ctx-schema-permits-arbitrary-domain-keys
  (testing "ctx may carry any domain keys"
    (is (not (m/explain schema/Ctx
                         {:agent/state {} :turn/messages [] :foo "bar"}))))
  (testing "ctx may opt into instrumentation"
    (is (not (m/explain schema/Ctx
                         {:chain/instrument? true
                          :chain/validate (fn [_] nil)})))))

(deftest ctx-schema-permits-traceability-keys
  (testing "ctx may carry session + user/assistant message IDs"
    (is (not (m/explain schema/Ctx
                         {:exchange/session-id       :test
                          :exchange/user-msg-id      "11111111-1111-1111-1111-111111111111"
                          :exchange/assistant-msg-id "22222222-2222-2222-2222-222222222222"}))))
  (testing "session-id may be a string"
    (is (not (m/explain schema/Ctx {:exchange/session-id "session-1"})))))

(deftest validator-accepts-clean-ctx
  (let [v (schema/make-validator)]
    (is (nil? (v {:agent/state {} :turn/messages []})))
    (is (nil? (v {:exchange/session-id :test
                  :exchange/user-msg-id "u1"
                  :exchange/assistant-msg-id "a1"})))))

(deftest validator-rejects-non-map
  (let [v (schema/make-validator)]
    (is (string? (v 42)))
    (is (string? (v nil)))
    (is (string? (v [])))))

(deftest validator-rejects-leaked-engine-keys
  (let [v (schema/make-validator)]
    (is (string? (v {::chain/queue []})))
    (is (string? (v {::chain/stack []})))
    (is (string? (v {::chain/error {}})))))
