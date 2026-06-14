(ns kschltz.agent.llm.client-test
  "Tests for the LlmClient protocol boundary.

   The protocol is the boundary between the interceptor engine
   and any LLM provider. This ns verifies the two shipped
   implementations:
     - stub-client — MVP default, deterministic echo
     - http-client — defers to kschltz.agent.llm.http via
       requiring-resolve (real HTTP path tested in
       kschltz.agent.llm.http-test).

   No real HTTP is exercised here."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.llm.client :as lcm-client]))

(deftest stub-client-echoes-last-user-message
  (testing "stub-client returns the last user message content"
    (let [client (lcm-client/stub-client)
          req    {:model    "stub/v0"
                  :messages [{:role "system" :content "sys"}
                             {:role "user" :content "ping"}]}
          resp   (lcm-client/-call client req)]
      (is (= "lateralus-v2 stub LLM echoed: ping"
             (get-in resp [:choices 0 :message :content])))
      (is (= "stub/v0" (:model resp)))
      (is (true? (:stub? resp))))))

(deftest stub-client-fallback-on-empty-messages
  (testing "stub-client falls back to '<no user text>' when messages are empty"
    (let [client (lcm-client/stub-client)
          resp   (lcm-client/-call client {:model "stub/v0" :messages []})]
      (is (= "lateralus-v2 stub LLM echoed: <no user text>"
             (get-in resp [:choices 0 :message :content]))))))

(deftest http-client-wrapper-is-a-fn
  (testing "http-client is a fn that defers to kschltz.agent.llm.http/http-client"
    (is (fn? lcm-client/http-client))
    ;; The real HTTP path is exercised in kschltz.agent.llm.http-test.
    ;; Here we only assert the wrapper exists and is callable without
    ;; throwing during construction.
    (let [client (lcm-client/http-client {:base-url "http://127.0.0.1:1"
                                          :model    "m"})]
      (is (some? client)
          "http-client constructor returns a client"))))
