(ns kschltz.agent.loop-test
  "Tests for the ReAct loop strategy and loop interceptors."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.llm.client :refer [LlmClient]]
            [kschltz.agent.loop :as loop]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.plugins.tools :as plugins.tools]
            [kschltz.agent.tool :as tool]))

(deftype EchoTool []
  tool/Tool
  (-name [_] "echo")
  (-description [_] "Echoes the message back.")
  (-input-schema [_] [:map [:msg :string]])
  (-output-schema [_] :string)
  (-invoke [_ args _ctx] (:msg args)))

(defn- echo-llm
  "Fake LLM that calls echo once, then returns a final text response."
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
                                              :function {:name "echo"
                                                         :arguments "{\"msg\":\"hi\"}"}}]}}]
           :model "fake/v0"}
          {:choices [{:message {:role "assistant"
                                :content "done"}}]
           :model "fake/v0"})))))

(defn- run-exchange
  ([llm prompt] (run-exchange llm prompt {}))
  ([llm prompt registry]
   (let [chain (plugin/assemble-chain [(plugins.base/base-plugin)
                                       (plugins.tools/tools-plugin registry)])]
     (chain/execute
      {:agent/state {:base-url "stub" :api-key nil :model "fake/v0"
                     :agent/system-message "You have tools."}
       :llm/client llm
       :exchange/user-text prompt
                      :agent/tool-loop-depth 0
                      :exchange/session-id :test-session
                      :exchange/user-msg-id "u1"
                      :exchange/assistant-msg-id "a1"}
      chain))))

(deftest react-loop-continues-when-tool-executed
  (let [out (run-exchange (echo-llm) "call echo" {"echo" (->EchoTool)})]
    (is (= "done" (:exchange/response out)))
    (is (= 1 (:agent/tool-loop-depth out)))
    (is (= 1 (count (:agent/all-tool-results out))))
    (is (= "tc1" (-> out :agent/all-tool-results first :call :id)))))

(deftest react-loop-stops-when-no-tool-executed
  (let [no-tool-llm (reify LlmClient
                      (-call [_client _req]
                        {:choices [{:message {:role "assistant"
                                              :content "no tools"}}]
                         :model "fake/v0"}))
        out (run-exchange no-tool-llm "hello" {"echo" (->EchoTool)})]
    (is (= "no tools" (:exchange/response out)))
    (is (= 0 (:agent/tool-loop-depth out)))))

(deftest react-loop-caps-depth
  (let [always-tool (reify LlmClient
                     (-call [_client _req]
                       {:choices [{:message {:role "assistant"
                                             :content ""
                                             :tool_calls [{:id "loop"
                                                           :type "function"
                                                           :function {:name "echo"
                                                                      :arguments "{\"msg\":\"x\"}"}}]}}]
                        :model "fake/v0"}))
        out (run-exchange always-tool "loop" {"echo" (->EchoTool)})]
    (is (= 5 (:agent/tool-loop-depth out))
        "loop stops at default max depth")))

(deftest loop-interceptors-validate-self-heal
  (testing "llm-call-with-self-heal passes a valid request through"
    (let [enter-fn (:enter (loop/llm-call-with-self-heal))
          ctx {:llm/request {:model "fake/v0"
                              :messages [{:role "user" :content "hi"}]}}]
      (is (= ctx (enter-fn ctx))
          "valid request is unchanged"))))
