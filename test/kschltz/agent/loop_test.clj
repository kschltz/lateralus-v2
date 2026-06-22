(ns kschltz.agent.loop-test
  "Tests for the ReAct loop strategy and loop interceptors."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
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
  (testing "the loop runs until the default max depth when each turn's
            tool call differs (stall detection does not fire)"
    (let [counter (atom 0)
          varying-tool (reify LlmClient
                         (-call [_client _req]
                           (let [n (swap! counter inc)]
                             {:choices [{:message {:role "assistant"
                                                   :content ""
                                                   :tool_calls [{:id "loop"
                                                                 :type "function"
                                                                 :function {:name "echo"
                                                                            :arguments (str "{\"msg\":\"x-" n "\"}")}}]}}]
                              :model "fake/v0"})))
          out (run-exchange varying-tool "loop" {"echo" (->EchoTool)})]
      (is (= 5 (:agent/tool-loop-depth out))
          "loop stops at default max depth"))))

(deftest loop-interceptors-validate-self-heal
  (testing "llm-call-with-self-heal passes a valid request through"
    (let [enter-fn (:enter (loop/llm-call-with-self-heal))
          ctx {:llm/request {:model "fake/v0"
                             :messages [{:role "user" :content "hi"}]}}]
      (is (= ctx (enter-fn ctx))
          "valid request is unchanged"))))

;; ---- ensure-text-response (item 2, 2026-06-22 improvement plan) ----
;;
;; The scripted LLM below pops responses from an atom the test holds on
;; to, so tests can inspect what was consumed and push more responses
;; mid-exchange. The atom is the single mutable seam; everything else
;; is plain data.

(defn- scripted-llm
  "Return an LlmClient that pops response maps from `queue-atom` (a
  mutable list of {:choices [...]} maps) in order. When the queue is
  empty, returns a default text response. Pass the atom in so tests
  can inspect consumption and push extra responses as needed."
  [queue-atom]
  (reify LlmClient
    (-call [_ _req]
      (let [next (first @queue-atom)]
        (swap! queue-atom rest)
        (or next
            {:choices [{:message {:role "assistant" :content "done"}}]
             :model "fake/v0"})))))

(defn- tool-call
  "Build an OpenAI-shaped tool_call map."
  [id name args]
  {:id id :type "function"
   :function {:name name :arguments args}})

(defn- text-choice
  "Build a choice map with assistant text content and no tool calls."
  [content]
  {:choices [{:message {:role "assistant" :content content}}]
   :model "fake/v0"})

(defn- tool-call-choice
  "Build a choice map with empty text and one tool call."
  [call]
  {:choices [{:message {:role "assistant" :content ""
                        :tool_calls [call]}}]
   :model "fake/v0"})

(defn- run-with-queue
  "Run an exchange with a scripted LLM backed by `queue` (a list of
  response maps). Returns the final ctx."
  [queue prompt registry]
  (run-exchange (scripted-llm (atom queue)) prompt registry))

(deftest ensure-text-response-fires-summary-when-loop-stops-blank
  (testing "tool ran, then model returned empty text + no calls -> a summary
            turn is forced so the user gets a textual answer"
    (let [out (run-with-queue
               [(tool-call-choice (tool-call "tc1" "echo" "{\"msg\":\"hi\"}"))
                (text-choice "")
                (text-choice "here is the answer")]
               "call echo"
               {"echo" (->EchoTool)})]
      (is (= "here is the answer" (:exchange/response out)))
      (is (seq (:agent/all-tool-results out)))
      (is (true? (:agent/summary-attempted out))))))

(deftest ensure-text-response-fires-empty-retry-when-nothing-ran
  (testing "model returned empty content with no tool calls on turn 1 -> an
            empty-response retry is forced"
    (let [out (run-with-queue
               [(text-choice "")
                (text-choice "hello back")]
               "hi"
               {"echo" (->EchoTool)})]
      (is (= "hello back" (:exchange/response out)))
      (is (true? (:agent/empty-retry-attempted out))))))

(deftest unregistered-tool-does-not-end-in-silence
  (testing "calling a tool not in the registry still yields a textual response
            via the summary path (no silent stop)"
    (let [out (run-with-queue
               [(tool-call-choice (tool-call "tc1" "nonexistent" "{}"))
                (text-choice "sorry, that tool is not available")]
               "call missing tool"
               {"echo" (->EchoTool)})]
      (is (= "sorry, that tool is not available" (:exchange/response out)))
      (is (some #(str/starts-with? (str (:result %)) "Tool '")
                (:agent/all-tool-results out))))))

(deftest ensure-text-response-is-noop-when-response-present
  (testing "a normal turn with a text response does not trigger summary/retry"
    (let [out (run-with-queue
               [(text-choice "immediate answer")]
               "hi"
               {"echo" (->EchoTool)})]
      (is (= "immediate answer" (:exchange/response out)))
      (is (nil? (:agent/summary-attempted out)))
      (is (nil? (:agent/empty-retry-attempted out))))))

(deftest loop-stall-detection-stops-repeated-calls
  (testing "when the model emits the SAME tool call twice in a row, the loop
            stops (stall) instead of looping forever, and ensure-text-response
            coerces a summary"
    (let [out (run-with-queue
               [(tool-call-choice (tool-call "tc1" "echo" "{\"msg\":\"same\"}"))
                (tool-call-choice (tool-call "tc2" "echo" "{\"msg\":\"same\"}"))
                (text-choice "stalled; here is the answer")]
               "loop"
               {"echo" (->EchoTool)})]
      (is (= "stalled; here is the answer" (:exchange/response out)))
      (is (= 1 (:agent/tool-loop-depth out))
          "stall detection stops the loop on the second identical call")
      (is (true? (:agent/summary-attempted out))))))
