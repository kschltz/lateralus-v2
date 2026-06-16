(ns kschltz.agent.examples.tools.loop-plugin-test
  "Tests for the tool-calling loop example plugin.

   These tests use a fake LlmClient so they run without network or
   Ollama. They verify the plugin chain assembly, tool execution, and
   the loop-back behavior."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.llm.client :refer [LlmClient]]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.examples.tools.loop-plugin :as loop-plugin]))

(defn- fake-tool-calling-llm
  "Fake LlmClient. On the first call it returns one tool call for
   `calculator/eval`; on the second call it returns a final text
   response. Assumes the request starts with the user prompt that
   triggered the loop."
  []
  (let [counter (atom 0)]
    (reify LlmClient
      (-call [_client req]
        (swap! counter inc)
        (if (= 1 @counter)
          {:choices [{:message {:role "assistant"
                                :content ""
                                :tool_calls [{:id "tc1"
                                              :type "function"
                                              :function {:name "calculator/eval"
                                                         :arguments "{\"expression\":\"(+ 1 2 3)\"}"}}]}}]
           :model "fake/v0"}
          {:choices [{:message {:role "assistant"
                                :content "The sum is 6."}}]
           :model "fake/v0"})))))

(defn- run-plugin-exchange
  "Execute the plugin chain with the given fake LLM and prompt."
  [llm prompt]
  (let [plugin (loop-plugin/loop-plugin)
        chain (plugin/assemble-chain [plugin])]
    (chain/execute
     {:agent/state {:base-url "stub" :api-key nil :model "fake/v0"
                    :agent/system-message "You are a helpful assistant with access to tools."}
      :llm/client llm
      :exchange/user-text prompt
      :exchange/session-id :test-session
      :exchange/user-msg-id "user-1"
      :exchange/assistant-msg-id "assistant-1"}
     chain)))

(deftest plugin-chain-assembles
  (testing "loop plugin assembles into a non-empty interceptor chain"
    (let [plugin (loop-plugin/loop-plugin)
          chain (plugin/assemble-chain [plugin])]
      (is (vector? chain))
      (is (pos? (count chain)))
      (is (every? map? chain))
      (is (every? #(or (:enter %) (:leave %) (:error %)) chain)))))

(deftest loop-executes-tool-and-returns-final-response
  (testing "plugin executes calculator/eval, loops back, and returns final text"
    (let [out (run-plugin-exchange (fake-tool-calling-llm) "What is 1 + 2 + 3?")]
      (is (= "The sum is 6." (:exchange/response out))
          "second LLM call response becomes the final response")
      (is (some? (:agent/all-tool-results out))
          "all tool results are accumulated")
      (is (= 1 (count (:agent/all-tool-results out)))
          "one tool result was recorded")
      (is (= "tc1" (-> out :agent/all-tool-results first :call :id))
          "tool call id is preserved")
      (is (= "6" (-> out :agent/all-tool-results first :result))
          "calculator/eval returned the correct sum")
      (is (= 1 (:agent/tool-loop-depth out))
          "loop depth incremented exactly once"))))

(deftest loop-depth-cap-prevents-runaway
  (testing "plugin stops looping when max depth is reached"
    (let [always-calls-llm
          (reify LlmClient
            (-call [_client _req]
              {:choices [{:message {:role "assistant"
                                    :content ""
                                    :tool_calls [{:id "tc-loop"
                                                  :type "function"
                                                  :function {:name "time/now"
                                                             :arguments "{}"}}]}}]
               :model "fake/v0"}))
          out (run-plugin-exchange always-calls-llm "keep calling tools")]
      (is (= 5 (:agent/tool-loop-depth out))
          "loop depth stops at the configured cap")
      (is (= 1 (count (:exchange/notified out)))
          "notify leave stage fires once for the whole exchange"))))

(deftest time-now-handler-returns-string
  (testing "time/now handler returns an ISO-8601-ish string"
    (let [result ((get-in loop-plugin/default-tools [:handlers "time/now"]) {})]
      (is (string? result))
      (is (re-find #"\d{4}-\d{2}-\d{2}T" result)))))
