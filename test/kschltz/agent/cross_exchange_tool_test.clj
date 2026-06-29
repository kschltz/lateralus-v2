(ns kschltz.agent.cross-exchange-tool-test
  "Regression guard for the cross-exchange tool result persistence fix.

   After the fix, `store-exchange` (in `kschltz.agent.interceptors`)
   appends assistant tool-calling messages (`:role \"assistant\"` with
   `:tool_calls`) AND matching tool-result messages (`:role \"tool\"`
   with `:tool_call_id`) into `:agent/history`, alongside the plain
   user/assistant text turns. `trim-history` then caps the history
   vector at 100 entries (bumped from 40 in 2026-06) and truncates individual tool `:content`
   strings to 2000 chars. The `:history-summarize` interceptor
   (added 2026-06) further compacts long histories into a single
   `[Conversation Summary - generated <ts>]` system message plus a
   protected window of recent turns; see `summarization-keeps-protected-tool-result`
   below for the guarantee that a protected `:role \"tool\"` message is
   never dropped by summarization.

   This test runs TWO exchanges on the SAME runtime so that whatever
   lands in `:agent/history` after exchange 1 is visible to the
   `compose-context` interceptor of exchange 2. With the fix in
   place, exchange 2's `:llm/request :messages` contains the tool
   message from exchange 1; without the fix it does not.

   The LLM is fully scripted (queue-atom pattern from
   `loop_test.clj`) — no network — so the test is deterministic."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.llm.client :refer [LlmClient]]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.plugins.summarizer :as plugins.summarizer]
            [kschltz.agent.plugins.tools :as plugins.tools]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.tool :as tool]))

;; ---- Tool under test ----

(deftype EchoTool []
  tool/Tool
  (-name [_] "echo")
  (-description [_] "Echoes the message back.")
  (-input-schema [_] [:map [:msg :string]])
  (-output-schema [_] :string)
  (-invoke [_ args _ctx] (:msg args)))

;; ---- Scripted LLM (same pattern as loop_test.clj) ----

(defn- tool-call
  "OpenAI-shaped tool_call map."
  [id name args]
  {:id id :type "function"
   :function {:name name :arguments args}})

(defn- tool-call-choice
  "Choice map with empty assistant content and one tool call."
  [call]
  {:choices [{:message {:role "assistant"
                        :content ""
                        :tool_calls [call]}}]
   :model "fake/v0"})

(defn- text-choice
  "Choice map with plain assistant text and no tool calls."
  [content]
  {:choices [{:message {:role "assistant" :content content}}]
   :model "fake/v0"})

(defn- scripted-llm
  "LlmClient that pops response maps from `queue-atom` (a list). When
   the queue is empty, returns a default text response. The atom is
   the only mutable seam; everything else is plain data."
  [queue-atom]
  (reify LlmClient
    (-call [_ _req]
      (let [next (first @queue-atom)]
        (swap! queue-atom rest)
        (or next
            {:choices [{:message {:role "assistant" :content "done"}}]
             :model "fake/v0"})))))

;; ---- Runtime plumbing ----

(defn- build-agent-map
  "Assemble a base-plugin + tools-plugin chain seeded with an EchoTool
   registry, wired into a runtime-ready agent-map."
  [registry llm]
  (let [chain (plugin/assemble-chain
               [(plugins.base/base-plugin)
                (plugins.tools/tools-plugin registry)])]
    {:agent/llm-client llm
     :exchange-chain   chain
     :initial-state    {:base-url             "stub"
                        :api-key              nil
                        :model                "fake/v0"
                        :agent/system-message "You have tools."}}))

(defn- req-messages [ctx]
  (-> ctx :llm/request :messages))

(defn- assistant-calls-echo-tc1?
  "True when `msg` is the assistant tool-calling turn that issued
   call id `tc1` for function `echo` with args `{\"msg\":\"hi\"}`.
   Used to gate that the persisted history includes the assistant
   side of the tool_call pairing (so the tool result is not an
   orphan)."
  [msg]
  (and (= "assistant" (:role msg))
       (let [calls (:tool_calls msg)
             call  (first calls)]
         (and (seq calls)
              (= "tc1" (:id call))
              (= "echo" (get-in call [:function :name]))
              (= "{\"msg\":\"hi\"}"
                 (get-in call [:function :arguments]))))))

;; ---- The test ----

(deftest cross-exchange-tool-result-persists-into-second-turn
  (testing "tool result from exchange 1 is visible to exchange 2
            (proves store-exchange now persists :role \"tool\" messages
             into :agent/history and compose-context includes them in
             exchange 2's outgoing LLM request)"
    (let [;; exchange 1 scripted responses:
          ;;   turn 1 — model emits tool_call(echo, "hi")
          ;;   turn 2 — model emits empty content (forces summary path)
          ;;   turn 3 — model emits the final text "got it: hi"
          ;;
          ;; exchange 2 scripted response:
          ;;   turn 1 — model emits plain text (no tool calls)
          queue       (atom [(tool-call-choice
                              (tool-call "tc1" "echo" "{\"msg\":\"hi\"}"))
                             (text-choice "")
                             (text-choice "got it: hi")
                             (text-choice "the tool said hi")])
          llm         (scripted-llm queue)
          agent-map   (build-agent-map {"echo" (->EchoTool)} llm)
          rt          (runtime/start agent-map "cross-exchange-test")
          ;; Exchange 1: drives the ReAct loop (tool_call -> summary).
          out1        (runtime/send-message rt "call echo with hi")
          ;; Exchange 2: a fresh user prompt. With the fix, the tool
          ;; result message from exchange 1 must be present in the
          ;; :llm/request sent to the model for this turn.
          out2        (runtime/send-message rt "what did the tool return?")
          ;; Inspect merged state after both exchanges.
          final-state (runtime/stop rt)
          history     (get-in final-state [:agent/history])
          req-msgs    (req-messages out2)]

      ;; Sanity: exchange 1 actually ran the echo tool and reached the
      ;; final summary text via the ensure-text-response interceptor.
      (is (= "got it: hi" (:exchange/response out1))
          "exchange 1 reaches the summary text \"got it: hi\"")
      (is (= 1 (count (:agent/all-tool-results out1)))
          "exchange 1 executed exactly one tool call")

      ;; Exchange 2 sanity.
      (is (= "the tool said hi" (:exchange/response out2))
          "exchange 2 returned a plain text response")

      ;; ---- THE FIX ----

      ;; 1. :agent/history after BOTH exchanges contains a :role "tool"
      ;;    entry carrying the persisted tool_call_id. Without the fix
      ;;    store-exchange only appends plain user/assistant turns and
      ;;    this entry is missing.
      (is (some #(and (= "tool" (:role %))
                      (= "tc1" (:tool_call_id %)))
                history)
          (str "history contains a persisted tool result for tc1; "
               "got history=" (pr-str history)))

      ;; 2. :agent/history also contains the assistant tool-calling turn
      ;;    (so the LLM sees the tool_calls/result pairing, not an
      ;;    orphan result message).
      (is (some assistant-calls-echo-tc1? history)
          (str "history contains the assistant tool_calls turn for "
               "echo/tc1; got history=" (pr-str history)))

      ;; 3. Exchange 2's outgoing LLM request messages carry the
      ;;    persisted tool message — i.e. compose-context read it
      ;;    from :agent/history and appended it before the new user
      ;;    turn. This is the cross-exchange visibility assertion.
      (is (some #(and (= "tool" (:role %))
                      (= "tc1" (:tool_call_id %))
                      (= "hi" (:content %)))
                req-msgs)
          (str "exchange 2 request includes the persisted tool "
               "message from exchange 1; got msgs="
               (pr-str (mapv #(select-keys % [:role :tool_call_id :content])
                             req-msgs))))

      ;; 4. trim-history sanity: every persisted tool message has a
      ;;    non-empty :content (it must not have been truncated to
      ;;    empty by the 2000-char cap or any other transform).
      (is (every? #(or (not= "tool" (:role %))
                       (and (string? (:content %))
                            (pos? (count (:content %)))))
                  history)
          "no persisted tool message has empty :content"))))

;; ---- Summarizer interaction -------------------------------------------
;;
;; A third concern: when the :history-summarize interceptor fires on a
;; long history, it must NOT drop a `:role "tool"` message (or its
;; matching assistant `:tool_calls` turn) that falls inside the
;; protected window of recent turns. The summarizer replaces only the
;; OLDEST contiguous block above the protected window with a single
;; `[Conversation Summary - generated <ts>]` system message; the
;; protected window is preserved verbatim.

(defn- summary-system-msg?
  "True for a system message emitted by the summarizer."
  [m]
  (and (= "system" (:role m))
       (str/starts-with? (or (:content m) "")
                         "[Conversation Summary - generated")))

(defn- fixed-summary-llm
  "Dedicated LlmClient for the summarizer that always returns `text`.
   Kept separate from the loop's scripted queue so summarizer calls do
   not consume responses meant for the ReAct loop. Records call count
   in `calls-atom`."
  [calls-atom text]
  (reify LlmClient
    (-call [_ _req]
      (swap! calls-atom inc)
      {:choices [{:message {:role "assistant" :content text}}]
       :model   "fake-summarizer"})))

(defn- summarizer-agent-map
  "Base + summarizer + tools chain. The summarizer is configured with a
   low trigger and a 2-pair protected window; it gets its OWN dedicated
   `summary-llm` so its LlmClient calls do not consume the loop's
   scripted queue. The tool exchange (exchange 3) lands inside the
   protected window while the older text turns are compacted."
  [registry llm summary-llm]
  (let [chain (plugin/assemble-chain
               [(plugins.base/base-plugin)
                (plugins.summarizer/summarizer-plugin
                  {:llm-client      summary-llm
                   :trigger         5
                   :protected-pairs 2
                   :model           "fake/v0"})
                (plugins.tools/tools-plugin registry)])]
    {:agent/llm-client llm
     :exchange-chain   chain
     :initial-state    {:base-url             "stub"
                        :api-key              nil
                        :model                "fake/v0"
                        :agent/system-message "You have tools."}}))

(deftest summarization-keeps-protected-tool-result
  (testing "when the :history-summarize interceptor fires after a
            tool-calling exchange, the protected :role \"tool\" message
            (and its matching assistant :tool_calls turn) survive in
            :agent/history and in the next exchange's :llm/request"
    (let [;; q1 and q2 are plain text. q3 drives the echo tool
            ;; (tool_call -> empty -> final text); after store-exchange the
            ;; body is 8 non-system messages (q1,a1,q2,a2,q3,asst-toolcall,
            ;; tool,asst-final) with 3 user turns, so with protected-pairs 2
            ;; the oldest block (q1,a1,q2) is non-empty and 8 > trigger 5
            ;; fires the summarizer once, leaving the tool pair inside the
            ;; protected window. q4 is plain text; it triggers a second
            ;; summarization but the tool pair stays protected. The
            ;; summarizer uses a DEDICATED LlmClient (not the loop queue)
            ;; so summarizer calls do not consume loop responses.
          sum-calls   (atom 0)
          summary-llm (fixed-summary-llm sum-calls "PROTECTED-SUMMARY")
          queue       (atom [(text-choice "a1")
                             (text-choice "a2")
                             (tool-call-choice
                              (tool-call "tc1" "echo" "{\"msg\":\"hi\"}"))
                             (text-choice "")
                             (text-choice "got it: hi")
                             (text-choice "a4")])
          llm         (scripted-llm queue)
          agent-map   (summarizer-agent-map {"echo" (->EchoTool)} llm summary-llm)
          rt          (runtime/start agent-map "summary-tool-prot")
          _out1       (runtime/send-message rt "q1")
          _out2       (runtime/send-message rt "q2")
          out3        (runtime/send-message rt "call echo with hi")
          out4        (runtime/send-message rt "q4")
          final-state (runtime/stop rt)
          history     (get-in final-state [:agent/history])
          req-msgs    (req-messages out4)]
      ;; Sanity: exchange 3 actually ran the echo tool and reached the
      ;; final text via ensure-text-response.
      (is (= "got it: hi" (:exchange/response out3))
          "exchange 3 reaches the final text \"got it: hi\"")
      ;; The summarizer fired at least once (after exchange 3).
      (is (pos? @sum-calls)
          "the dedicated summarizer LlmClient was invoked")
      ;; Exactly one summary system message persisted (each summarizing
      ;; exchange rewrites the prior summary, so the count stays at 1).
      (is (= 1 (count (filter summary-system-msg? history)))
          (str "exactly one summary system message is present; got history="
               (pr-str history)))
      ;; The protected tool result survived summarization.
      (is (some #(and (= "tool" (:role %))
                      (= "tc1" (:tool_call_id %))
                      (= "hi" (:content %))) history)
          (str "the protected tool result for tc1 survived summarization; "
               "got history=" (pr-str history)))
      ;; The matching assistant tool_calls turn survived too (no orphan).
      (is (some assistant-calls-echo-tc1? history)
          "the assistant tool_calls turn for tc1 survived (no orphan)")
      ;; The tool result is visible to exchange 4's LLM request.
      (is (some #(and (= "tool" (:role %))
                      (= "tc1" (:tool_call_id %))) req-msgs)
          (str "exchange 4 request includes the persisted tool message; "
               "got msgs="
               (pr-str (mapv #(select-keys % [:role :tool_call_id :content])
                             req-msgs))))
      ;; The summary is also in exchange 4's request, preceding the
      ;; protected window.
      (is (some summary-system-msg? req-msgs)
          "exchange 4 request includes the summary system message")
      ;; The latest turns are present verbatim.
      (is (some #(= "q4" (:content %)) history)
          "the latest user turn is present verbatim")
      (is (some #(= "a4" (:content %)) history)
          "the latest assistant turn is present verbatim"))))