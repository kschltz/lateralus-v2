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

;; ---- regression: follow-up chain must not duplicate tool-result blocks ----
;; Root cause of the "agent hands off the turn before it is complete" symptom
;; (audit 2026-06-24): compose-tool-results was positioned BEFORE dispatch-tools
;; in -follow-up-chain, so every follow-up turn re-appended the PREVIOUS turn's
;; stale [assistant(tool_calls), tool*] block to :llm/request :messages. The fix
;; moves compose-tool-results to AFTER dispatch-tools. This test pins the
;; invariant: exactly one assistant(tool_calls) block per turn, each tool_call_id
;; appears in exactly one {:role "tool"} message.

(defn- fresh-ctx-for-chain
  "Build the ctx the base chain expects, with a seeded tool registry and
  loop-opts."
  [llm registry loop-opts]
  {:agent/state {:base-url "stub" :api-key nil :model "fake/v0"
                 :agent/system-message "You have tools."}
   :llm/client llm
   :exchange/user-text "do it"
   :agent/tool-loop-depth 0
   :agent/loop-opts loop-opts
   :exchange/session-id :test-session
   :exchange/user-msg-id "u1"
   :exchange/assistant-msg-id "a1"})

(defn- counting-tool-call-llm
  "LLM that emits a single echo tool_call every turn with a unique id+arg
  for `n` turns, then a final text response. Returns [llm atom-of-turn]."
  [n]
  (let [turn (atom 0)]
    [(reify LlmClient
       (-call [_ _req]
         (let [t (swap! turn inc)]
           (if (<= t n)
             {:choices [{:message {:role "assistant" :content ""
                                   :tool_calls [{:id (str "tc" t) :type "function"
                                                 :function {:name "echo"
                                                            :arguments (str "{\\\"msg\\\":\\\"turn" t "\\\"}")}}]}}]
              :model "fake/v0"}
             {:choices [{:message {:role "assistant" :content (str "final answer after " n " tools")}}]
              :model "fake/v0"})))) turn]))

(deftest follow-up-chain-appends-each-turns-results-exactly-once
  (testing "after a 3-tool ReAct loop, :llm/request :messages holds exactly
            one assistant(tool_calls) block per tool turn and each tool_call_id
            appears in exactly one tool result message — no duplicates"
    (let [[llm turn-atom] (counting-tool-call-llm 3)
          registry {"echo" (->EchoTool)}
          chain (plugin/assemble-chain [(plugins.base/base-plugin)
                                        (plugins.tools/tools-plugin registry)])
          out (chain/execute (fresh-ctx-for-chain llm registry nil) chain)
          msgs (get-in out [:llm/request :messages])]
      (is (= 4 @turn-atom)
          "sanity: 3 tool turns + 1 final text turn = 4 LLM calls")
      (let [asst-tool-blocks (filter #(and (map? %)
                                           (= "assistant" (:role %))
                                           (seq (:tool_calls %)))
                                     msgs)
            tool-result-msgs (filter #(and (map? %) (= "tool" (:role %))) msgs)
            ids-in-asst-blocks (mapcat #(map :id (:tool_calls %)) asst-tool-blocks)
            ids-in-results (map :tool_call_id tool-result-msgs)]
        (is (= 3 (count asst-tool-blocks))
            "exactly one assistant(tool_calls) block per tool turn (no duplicates)")
        (is (= ["tc1" "tc2" "tc3"] ids-in-asst-blocks)
            "the three assistant tool_calls are tc1, tc2, tc3 in order")
        (is (= 3 (count tool-result-msgs))
            "exactly three tool result messages (one per call)")
        (is (= (set ids-in-asst-blocks) (set ids-in-results))
            "every tool_call_id is paired with exactly one tool result")
        (is (= 3 (count (set ids-in-results)))
            "no tool_call_id is duplicated across result messages")))))

(deftest follow-up-chain-results-visible-to-next-turns-llm-call
  (testing "turn N's tool results are in :llm/request :messages before turn N+1's
            LLM call — the model always sees the prior turn's outcome (one-turn
            ReAct cadence), not stale/duplicate context"
    (let [seen-messages (atom [])
          llm (reify LlmClient
                (-call [_ req]
                  (swap! seen-messages conj (:messages req))
                  ;; turn 1: tool; turn 2: tool; turn 3: text
                  (let [n (count @seen-messages)]
                    (if (<= n 2)
                      {:choices [{:message {:role "assistant" :content ""
                                            :tool_calls [{:id (str "tc" n) :type "function"
                                                          :function {:name "echo"
                                                                     :arguments (str "{\\\"msg\\\":\\\"t" n "\\\"}")}}]}}]
                       :model "fake/v0"}
                      {:choices [{:message {:role "assistant" :content "done"}}]
                       :model "fake/v0"}))))
          registry {"echo" (->EchoTool)}
          chain (plugin/assemble-chain [(plugins.base/base-plugin)
                                        (plugins.tools/tools-plugin registry)])
          _ (chain/execute (fresh-ctx-for-chain llm registry nil) chain)
          ;; turn-2 call (2nd element) must include turn-1's tool result
          turn2-msgs (nth @seen-messages 1)
          turn3-msgs (nth @seen-messages 2)]
      (is (some #(and (= "tool" (:role %)) (= "tc1" (:tool_call_id %))) turn2-msgs)
          "turn 2's LLM call sees turn-1's tool result (tc1)")
      (is (some #(and (= "tool" (:role %)) (= "tc2" (:tool_call_id %))) turn3-msgs)
          "turn 3's LLM call sees turn-2's tool result (tc2)")
      (is (= 1 (count (filter #(and (= "tool" (:role %)) (= "tc1" (:tool_call_id %))) turn2-msgs)))
          "turn-1's tool result appears exactly once in turn 2's request (no duplicate)"))))

;; ---- audit 2026-06-24 follow-on fixes: #2 force-summary at cap-stop,
;;      #3 truncation feedback, #6 in-loop trim, #4 exception-safe persist ----

(defn- preamble-plus-tool-llm
  "LLM that emits non-blank preamble ('Let me check...') + an echo
  tool_call on every tool turn (unique arg so stall detection doesn't
  trip), and a clean text answer once :tools is stripped from the
  request (the summary call). Used to test that a cap-stop with a
  preamble still forces a summary instead of delivering the preamble."
  []
  (let [n (atom 0)]
    (reify LlmClient
      (-call [_ req]
        (if (contains? req :tools)
          (let [t (swap! n inc)]
            {:choices [{:message {:role "assistant" :content "Let me check that for you."
                                  :tool_calls [{:id (str "tc" t) :type "function"
                                                :function {:name "echo"
                                                           :arguments (str "{\\\"msg\\\":\\\"n" t "\\\"}")}}]}}]
             :model "fake/v0"})
          {:choices [{:message {:role "assistant" :content "here is the final answer"}}]
           :model "fake/v0"})))))

(deftest cap-stop-with-preamble-forces-summary
  (testing "audit #2: when the loop stops on a depth cap with a non-blank
            preamble alongside tool_calls, ensure-text-response forces a
            summary instead of delivering the preamble as the final answer"
    (let [out (run-with-loop-opts (preamble-plus-tool-llm) "loop" {"echo" (->EchoTool)}
                                  {:max-loop-depth 2})]
      (is (pos? (:agent/summary-attempts out 0))
          "a summary call was forced despite the non-blank preamble")
      (is (= "here is the final answer" (:exchange/response out))
          "the final response is the summary, not the preamble")
      (is (not= "Let me check that for you." (:exchange/response out))
          "the preamble was NOT delivered as the final answer"))))

(defn- one-turn-five-calls-llm
  "LLM that on turn 1 emits 5 echo tool_calls in a single message, then
  a text 'done' on turn 2."
  []
  (let [five (mapv (fn [i] {:id (str "tc" i) :type "function"
                            :function {:name "echo"
                                       :arguments (str "{\\\"msg\\\":\\\"d" i "\\\"}")}})
                   (range 5))
        called (atom false)]
    (reify LlmClient
      (-call [_ _req]
        (if (compare-and-set! called false true)
          {:choices [{:message {:role "assistant" :content ""
                                :tool_calls five}}]
           :model "fake/v0"}
          {:choices [{:message {:role "assistant" :content "done"}}]
           :model "fake/v0"})))))

(deftest truncated-tool-calls-are-surfaced-to-the-model
  (testing "audit #3: when :max-tool-calls-per-turn drops calls, a system
            message telling the model about the truncation is injected into
            :llm/request :messages (not just silently recorded as a flag)"
    (let [out (run-with-loop-opts (one-turn-five-calls-llm) "loop" {"echo" (->EchoTool)}
                                  {:max-tool-calls-per-turn 2})
          msgs (get-in out [:llm/request :messages])
          truncation-msg (some #(and (map? %)
                                     (= "system" (:role %))
                                     (str/includes? (:content %) "Truncated 3 tool call(s)")
                                     %)
                               msgs)]
      (is (= 3 (:agent/tool-calls-dropped out 0))
          "3 of 5 calls were dropped")
      (is (some? truncation-msg)
          "a system message about the 3 truncated calls is in the request messages"))))

(defn- many-turn-varying-llm
  "LLM that emits a unique-arg echo tool_call for `n` tool turns, then
  a text answer once :tools is stripped (the summary). Used to grow the
  in-exchange messages vector past the trim cap."
  [n]
  (let [turn (atom 0)]
    (reify LlmClient
      (-call [_ req]
        (if (contains? req :tools)
          (let [t (swap! turn inc)]
            (if (<= t n)
              {:choices [{:message {:role "assistant" :content ""
                                    :tool_calls [{:id (str "tc" t) :type "function"
                                                  :function {:name "echo"
                                                             :arguments (str "{\\\"msg\\\":\\\"turn" t "\\\"}")}}]}}]
               :model "fake/v0"}
              {:choices [{:message {:role "assistant" :content "summarized"}}]
               :model "fake/v0"}))
          {:choices [{:message {:role "assistant" :content "summarized"}}]
           :model "fake/v0"})))))

(deftest in-loop-trim-keeps-messages-bounded
  (testing "audit #6: trim-history runs on follow-up turns (via
            compose-tool-results), so a 25-turn ReAct loop does NOT grow
            :llm/request :messages to ~52 entries; the oldest tool results
            are trimmed while the newest survive"
    (let [out (run-with-loop-opts (many-turn-varying-llm 25) "loop" {"echo" (->EchoTool)}
                                  {:max-loop-depth 25})
          msgs (get-in out [:llm/request :messages])
          tool-ids (set (map :tool_call_id (filter #(and (map? %) (= "tool" (:role %))) msgs)))]
      (is (< (count msgs) 45)
          "messages stay bounded (without in-loop trim, ~52 entries would accumulate)")
      (is (not (contains? tool-ids "tc1"))
          "the oldest tool result (tc1) was trimmed off")
      (is (contains? tool-ids "tc25")
          "the newest tool result (tc25) survived the trim window"))))

(defn- throw-on-follow-up-llm
  "LLM that returns an echo tool_call on turn 1, then THROWS on the
  turn-2 follow-up call — simulating a provider/transport failure mid-loop
  after tool results have accumulated into :agent/all-tool-results."
  []
  (let [called (atom false)]
    (reify LlmClient
      (-call [_ _req]
        (if (compare-and-set! called false true)
          {:choices [{:message {:role "assistant" :content ""
                                :tool_calls [{:id "tc1" :type "function"
                                              :function {:name "echo"
                                                         :arguments "{\\\"msg\\\":\\\"x\\\"}"}}]}}]
           :model "fake/v0"}
          (throw (ex-info "simulated provider failure on follow-up"
                          {:chain/stage :llm})))))))

(deftest mid-loop-throw-persists-partial-tool-history
  (testing "audit #4: when a throw in :llm/:tools/:finalize prevents
            store-exchange (slot :history) from ever entering, error-boundary
            snapshots the accumulated :agent/all-tool-results into
            :agent/state-delta :agent/history so the partial transcript
            survives to the next exchange"
    (let [out (run-with-loop-opts (throw-on-follow-up-llm) "loop" {"echo" (->EchoTool)} nil)
          history (get-in out [:agent/state-delta :agent/history])]
      (is (some? (:error/raised out))
          "the throw was captured as :error/raised (not rethrown)")
      (is (some #(and (map? %) (= "tool" (:role %)) (= "tc1" (:tool_call_id %))) history)
          "the partial tool result (tc1) made it into the persisted history")
      (is (some #(and (map? %) (= "user" (:role %))) history)
          "the user turn is in the partial history")
      (is (some #(and (map? %) (= "assistant" (:role %)) (seq (:tool_calls %))) history)
          "the assistant tool-calling turn is reconstructed in the partial history"))))
