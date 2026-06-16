(ns kschltz.agent.examples.tools.loop-plugin-test
  "Tests for the tool-calling loop example wiring.

   These tests use a fake LlmClient so they run without network or
   Ollama. They verify the tool-calling loop is now provided by the
   core base chain and that the example tools execute and loop back
   correctly."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.llm.client :refer [LlmClient]]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.plugins.tools :as plugins.tools]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.examples :as tools.examples]))

(defn- fake-tool-calling-llm
  "Fake LlmClient. On the first call it returns one tool call for
   `calculator/eval`; on the second call it returns a final text
   response."
  []
  (let [counter (atom 0)]
    (reify LlmClient
      (-call [_client _req]
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

(defn- run-exchange
  "Execute the default chain (base plugin + tools plugin) with the given
   fake LLM, prompt, and optional tool registry."
  ([llm prompt] (run-exchange llm prompt {}))
  ([llm prompt registry]
   (let [chain (plugin/assemble-chain [(plugins.base/base-plugin)
                                       (plugins.tools/tools-plugin registry)])]
     (chain/execute
      {:agent/state {:base-url "stub" :api-key nil :model "fake/v0"
                     :agent/system-message "You are a helpful assistant with access to tools."}
       :llm/client llm
       :exchange/user-text prompt
       :exchange/session-id :test-session
       :exchange/user-msg-id "user-1"
       :exchange/assistant-msg-id "assistant-1"}
      chain))))

(deftest base-chain-contains-tool-interceptors
  (testing "base plugin now includes tool-calling interceptors"
    (let [chain (plugin/assemble-chain [(plugins.base/base-plugin)])]
      (is (some #(= :kschltz.agent.loop/inject-tools (:name %)) chain))
      (is (some #(= :kschltz.agent.loop/dispatch-tools (:name %)) chain))
      (is (some #(= :kschltz.agent.loop/tool-loop (:name %)) chain))
      (is (some #(= :kschltz.agent.loop/compose-tool-results (:name %)) chain)))))

(deftest loop-executes-tool-and-returns-final-response
  (testing "core loop executes calculator/eval, loops back, and returns final text"
    (let [registry (tools.examples/example-registry)
          out (run-exchange (fake-tool-calling-llm) "What is 1 + 2 + 3?" registry)]
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
  (testing "loop stops when max depth is reached"
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
          out (run-exchange always-calls-llm "keep calling tools" (tools.examples/example-registry))]
      (is (= 5 (:agent/tool-loop-depth out))
          "loop depth stops at the configured cap")
      (is (= 1 (count (:exchange/notified out)))
          "notify leave stage fires once for the whole exchange"))))

(deftest time-now-tool-returns-string
  (testing "time/now Tool returns an ISO-8601-ish string"
    (let [tool (tools.examples/time-now)
          result (tool/-invoke tool {})]
      (is (string? result))
      (is (re-find #"\d{4}-\d{2}-\d{2}T" result)))))

(deftest calculator-eval-tool-computes
  (testing "calculator/eval Tool computes prefix arithmetic"
    (let [tool (tools.examples/calculator-eval)
          result (tool/-invoke tool {:expression "(+ 1 2 3)"})]
      (is (= "6" result)))))

(deftest integrant-tool-components-build
  (testing "example tools can be built via Integrant"
    (let [system (ig/init {:lateralus/example-tools {}})]
      (is (map? (:lateralus/example-tools system)))
      (is (tool/tool? (get-in system [:lateralus/example-tools "time/now"])))
      (is (tool/tool? (get-in system [:lateralus/example-tools "calculator/eval"])))
      (ig/halt! system))))
