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
      (is (pos? (:agent/summary-attempts out 0))))))

(deftest ensure-text-response-fires-empty-retry-when-nothing-ran
  (testing "model returned empty content with no tool calls on turn 1 -> an
            empty-response retry is forced"
    (let [out (run-with-queue
               [(text-choice "")
                (text-choice "hello back")]
               "hi"
               {"echo" (->EchoTool)})]
      (is (= "hello back" (:exchange/response out)))
      (is (pos? (:agent/empty-retry-attempts out 0))))))

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
      (is (zero? (:agent/summary-attempts out 0)))
      (is (zero? (:agent/empty-retry-attempts out 0))))))

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
      (is (pos? (:agent/summary-attempts out 0))))))

;; ---- empty-summary-call fix (2026-06-22 investigation) ----
;;
;; The summary mini-chain used to advertise :tools on the summary LLM
;; call, so tool-happy models (kimi-k2.7-code:cloud) returned tool_calls
;; instead of text -> empty :exchange/response. The fix strips :tools
;; from the summary/empty-retry request and bumps the cap to 2 attempts.

(deftest summary-call-strips-tools-so-model-cannot-re-call
  (testing "ensure-text-response's summary LLM call has :tools removed
            from :llm/request — even if the registry had tools, the
            summary is text-only. Regression guard for the 2026-06-22
            bug where the summary re-called file/read instead of
            producing text."
    (let [req-views (atom [])
          observer  (reify LlmClient
                      (-call [_ req]
                        (swap! req-views conj req)
                         ;; turn 1: emit a tool_call so the loop runs,
                         ;; then the model returns empty + no calls on
                         ;; the follow-up, triggering the summary path.
                        (condp = (count @req-views)
                          1 {:choices [{:message {:role "assistant"
                                                  :content ""
                                                  :tool_calls [{:id "tc1" :type "function"
                                                                :function {:name "echo"
                                                                           :arguments "{\"msg\":\"hi\"}"}}]}}]
                             :model "fake/v0"}
                          2 {:choices [{:message {:role "assistant" :content ""}}]
                             :model "fake/v0"}
                          3 {:choices [{:message {:role "assistant" :content "FINAL ANSWER"}}]
                             :model "fake/v0"})))
          out       (run-exchange observer "call echo" {"echo" (->EchoTool)})
          summary-req (get @req-views 2)]   ; the 3rd call is the summary
      (is (>= (count @req-views) 3) "at least 3 LLM calls ran (tool, empty, summary)")
      (is (nil? (:tools summary-req))
          "the summary LLM call must NOT advertise :tools")
      (is (= "FINAL ANSWER" (:exchange/response out))
          (str "with :tools stripped, the model must produce text; got response="
               (pr-str (:exchange/response out))))
      (is (not (true? (:agent/summary-failed? out)))
          "summary should not fail when :tools is stripped"))))

(deftest summary-returns-tool-calls-anyway-sets-summary-failed-flag
  (testing "when the summary call STILL returns tool_calls (an
            adversarial provider that ignores the absent :tools key),
            the retry cap kicks in, sets :agent/summary-failed?, and
            the response stays blank so the CLI fallback engages."
    (let [out (run-with-queue
               [(tool-call-choice (tool-call "tc1" "echo" "{\"msg\":\"x\"}"))
                (text-choice "")
                 ;; summary call #1 — returns tool_calls again
                (tool-call-choice (tool-call "tc2" "echo" "{\"msg\":\"x\"}"))
                 ;; summary call #2 — returns tool_calls again (cap hit)
                (tool-call-choice (tool-call "tc3" "echo" "{\"msg\":\"x\"}"))
                 ;; never reached
                (text-choice "UNREACHED")]
               "call echo"
               {"echo" (->EchoTool)})]
      (is (true? (:agent/summary-failed? out))
          (str "summary-failed? must be set after the cap; got "
               (pr-str (select-keys out [:agent/summary-failed?
                                         :agent/summary-attempts]))))
      (is (= 2 (:agent/summary-attempts out 0))
          "summary must attempt exactly 2 times before giving up")
      (is (str/blank? (:exchange/response out))
          "response stays blank when summary exhausts its cap"))))

;; ---- configurable tool-call caps (2026-06-22) ----

(defn- run-with-loop-opts
  "Run an exchange with an LlmClient + loop-opts seeded on ctx, so the
  caps are exercised without wiring a full Integrant system."
  [llm prompt registry loop-opts]
  (let [chain (plugin/assemble-chain [(plugins.base/base-plugin)
                                      (plugins.tools/tools-plugin registry)])]
    (chain/execute
     {:agent/state {:base-url "stub" :api-key nil :model "fake/v0"
                    :agent/system-message "You have tools."}
      :llm/client llm
      :exchange/user-text prompt
      :agent/tool-loop-depth 0
      :agent/loop-opts loop-opts
      :exchange/session-id :test-session
      :exchange/user-msg-id "u1"
      :exchange/assistant-msg-id "a1"}
     chain)))

(defn- varying-args-llm
  "LLM that emits a tool_call(echo) every turn with a unique arg so stall
  detection (identical-repeat) never trips."
  []
  (let [n (atom 0)]
    (reify LlmClient
      (-call [_ _req]
        (swap! n inc)
        {:choices [{:message {:role "assistant" :content ""
                              :tool_calls [{:id (str "tc" @n) :type "function"
                                            :function {:name "echo"
                                                       :arguments (str "{\"msg\":\"n" @n "\"}")}}]}}]
         :model "fake/v0"}))))

(defn- alternating-args-llm
  "LLM that emits a tool_call(echo) every turn alternating between two
  args (a rut stall detection does NOT catch — only identical repeats)."
  []
  (let [n (atom 0)]
    (reify LlmClient
      (-call [_ _req]
        (swap! n inc)
        {:choices [{:message {:role "assistant" :content ""
                              :tool_calls [{:id (str "tc" @n) :type "function"
                                            :function {:name "echo"
                                                       :arguments (str "{\"msg\":\"n" (mod @n 2) "\"}")}}]}}]
         :model "fake/v0"}))))

(deftest max-loop-depth-config-raises-cap-above-default
  (testing ":max-loop-depth in loop-opts overrides the default 5 — the
            loop runs 7 follow-up turns instead of stopping at 5"
    (let [out (run-with-loop-opts (varying-args-llm) "loop" {"echo" (->EchoTool)}
                                  {:max-loop-depth 7})]
      (is (= 7 (:agent/tool-loop-depth out))
          "loop runs to the configured cap of 7, not the default 5"))))

(deftest max-tool-calls-per-exchange-stops-the-rut
  (testing ":max-tool-calls-per-exchange stops the loop when cumulative
            tool calls hit the cap, even with alternating (non-identical)
            calls that stall detection would not catch, and sets
            :agent/tool-cap-hit"
    (let [out (run-with-loop-opts (alternating-args-llm) "loop" {"echo" (->EchoTool)}
                                  {:max-tool-calls-per-exchange 3})]
      (is (true? (:agent/tool-cap-hit out))
          "the per-exchange cap must set :agent/tool-cap-hit")
      (is (>= 3 (count (:agent/all-tool-results out)))
          "the loop stops by the 3rd tool call"))))

(deftest max-tool-calls-per-turn-caps-dispatch
  (testing ":max-tool-calls-per-turn limits how many tool_calls execute in
            one turn; only the capped number run (all-tool-results) and
            the dropped count is recorded in :agent/tool-calls-dropped"
    (let [five-calls (mapv (fn [i] {:id (str "tc" i) :type "function"
                                    :function {:name "echo"
                                               :arguments (str "{\"msg\":\"d" i "\"}")}})
                           (range 5))
          one-turn-five {:choices [{:message {:role "assistant" :content ""
                                              :tool_calls five-calls}}]
                         :model "fake/v0"}
          llm (scripted-llm (atom [one-turn-five
                                   (text-choice "")
                                   (text-choice "done")]))
          out (run-with-loop-opts llm "loop" {"echo" (->EchoTool)}
                                  {:max-tool-calls-per-turn 2})]
      (is (= 2 (count (:agent/all-tool-results out)))
          "only 2 of the 5 emitted tool_calls run (cap=2)")
      (is (= 3 (:agent/tool-calls-dropped out 0))
          "the 3 dropped calls are recorded in :agent/tool-calls-dropped"))))

(deftest loop-opts-absent-falls-back-to-defaults
  (testing "with no loop-opts on ctx, the default max-loop-depth 5 still
            applies and no caps trip"
    (let [out (run-with-loop-opts (varying-args-llm) "loop" {"echo" (->EchoTool)} nil)]
      (is (= 5 (:agent/tool-loop-depth out))
          "default cap of 5 applies when loop-opts is absent")
      (is (nil? (:agent/tool-cap-hit out))
          "no per-exchange cap trips when unset"))))
