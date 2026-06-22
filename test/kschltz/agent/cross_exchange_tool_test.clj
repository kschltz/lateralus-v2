(ns kschltz.agent.cross-exchange-tool-test
  "Regression guard for the cross-exchange tool result persistence fix.

   After the fix, `store-exchange` (in `kschltz.agent.interceptors`)
   appends assistant tool-calling messages (`:role \"assistant\"` with
   `:tool_calls`) AND matching tool-result messages (`:role \"tool\"`
   with `:tool_call_id`) into `:agent/history`, alongside the plain
   user/assistant text turns. `trim-history` then caps the history
   vector at 40 entries and truncates individual tool `:content`
   strings to 2000 chars.

   This test runs TWO exchanges on the SAME runtime so that whatever
   lands in `:agent/history` after exchange 1 is visible to the
   `compose-context` interceptor of exchange 2. With the fix in
   place, exchange 2's `:llm/request :messages` contains the tool
   message from exchange 1; without the fix it does not.

   The LLM is fully scripted (queue-atom pattern from
   `loop_test.clj`) — no network — so the test is deterministic."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.llm.client :refer [LlmClient]]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
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