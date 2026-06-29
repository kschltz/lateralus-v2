(ns kschltz.agent.runtime-test
  "Tests for the agent outer-loop runtime.

   The runtime is the thin layer between the caller (CLI, test,
   web server) and the chain. Its job is bookkeeping only:
     1. Build a per-exchange ctx with traceability IDs
        (session-id, user-msg-id, assistant-msg-id)
     2. Call chain/execute with the chain from agent-map
     3. Merge :agent/state-delta into the runtime's state atom
     4. Expose the current state via `stop`

   MVP scope: single-threaded. send-message runs the chain
   synchronously on the caller thread. A queue + worker thread
   is a follow-up — there is no use case for it in the MVP CLI
   (single user, one prompt at a time)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.llm.client :refer [LlmClient]]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.plugins.summarizer :as plugins.summarizer]
            [kschltz.agent.plugins.tools :as plugins.tools]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.tool :as tool]))

;; ---- Helpers ----

(defn- default-exchange-chain []
  (plugin/assemble-chain [(plugins.base/base-plugin)]))

(defn- noop-chain
  "A trivial chain that records the ctx on an atom and returns it.
   Useful for asserting what the runtime injected."
  [events-atom]
  [{:name    ::record
    :enter   (fn [ctx]
               (swap! events-atom conj [:enter ctx])
               ctx)
    :leave   (fn [ctx]
               (swap! events-atom conj [:leave ctx])
               (assoc ctx :agent/state-delta {:ran? true}))}])

(defn- echo-chain
  "A chain that emits :agent/state-delta {:n 1} on every send. The
   runtime's plain-merge semantic replaces the previous :n value
   with the new one, so the state always reads {:n 1} regardless
   of how many times send-message is called. A separate test
   (counter-chain) verifies that the runtime preserves the base
   state across calls."
  []
  [{:name ::echo
    :leave (fn [ctx]
             (assoc ctx :agent/state-delta {:n 1}))}])

(defn- counter-chain
  "A chain that increments :n in the base state and emits it as
   state-delta. Tests that the runtime threads the base state
   through correctly."
  []
  [{:name ::counter
    :leave (fn [ctx]
             (let [prev (:n (:agent/state ctx) 0)]
               (assoc ctx :agent/state-delta {:n (inc prev)})))}])

(defn- throwing-chain
  "A chain whose middle stage throws. The default chain's
   error-boundary catches the throw, annotates the ctx with
   :error/raised, and lets the :leave phases still run. This is
   the realistic failure mode: a custom plugin throws, the engine
   keeps going, and the runtime sees a final ctx with :error/raised."
  []
  [ix/error-boundary
   {:name ::bomb
    :enter (fn [_ctx] (throw (ex-info "boom" {:boom true})))}
   {:name ::post-leave
    :leave (fn [ctx] (assoc ctx :post-leave-ran? true))}])

(defn- user-state
  "Return the runtime state with runtime bookkeeping keys removed.
   Useful for asserting on user-visible state while ignoring the
   session-id, token usage and request message counters the runtime
   itself maintains."
  [runtime]
  (dissoc (runtime/stop runtime)
          :agent/session-id
          :agent/token-usage
          :agent/last-request-messages))

;; ---- Tests ----

(deftest start-creates-runtime
  (testing "start with just an agent-map creates a runtime"
    (let [r (runtime/start {:exchange-chain (default-exchange-chain)})]
      (is (some? r) "runtime is created")
      (is (satisfies? runtime/AgentRuntime r) "runtime satisfies the AgentRuntime protocol"))))

(deftest start-falls-back-to-default-chain
  (testing "an agent-map without :exchange-chain still works; the
   default chain is used. The MVP CLI hits this path (the user
   has not provided a custom chain)."
    (let [runtime (runtime/start {})]
      (is (some? (try
                   (runtime/send-message runtime "hi")
                   :ok
                   (catch Throwable _t
                     :threw)))
          "send-message runs the default chain end-to-end without throwing"))))

(deftest start-with-explicit-session-id
  (testing "3-arity start honors an explicit session-id"
    (let [sid "test-session-42"
          r   (runtime/start {:exchange-chain (default-exchange-chain)}
                             sid)]
      (is (= sid (runtime/session-id r))
          "session-id is stored on the runtime"))))

(deftest send-message-injects-traceability-ids
  (testing "send-message generates session-id, user-msg-id, assistant-msg-id
   on the per-exchange ctx"
    (let [events  (atom [])
          runtime (runtime/start {:exchange-chain (noop-chain events)})]
      (runtime/send-message runtime "hello")
      (let [entered-ctx (-> @events first second)]
        (is (some? (:exchange/session-id entered-ctx))
            "session-id is present on the per-exchange ctx")
        (is (some? (:exchange/user-msg-id entered-ctx))
            "user-msg-id is present on the per-exchange ctx")
        (is (some? (:exchange/assistant-msg-id entered-ctx))
            "assistant-msg-id is present on the per-exchange ctx")
        (is (= "hello" (:exchange/user-text entered-ctx))
            "user-text is present on the per-exchange ctx")
        (is (= (runtime/session-id runtime) (:exchange/session-id entered-ctx))
            "the per-exchange session-id matches the runtime's session-id")))))

(deftest send-message-runs-chain-synchronously
  (testing "send-message runs the chain on the caller thread (MVP simplification).
   The :enter and :leave events both fire before send-message returns."
    (let [events  (atom [])
          runtime (runtime/start {:exchange-chain (noop-chain events)})]
      (runtime/send-message runtime "hi")
      (is (= 2 (count @events))
          "one :enter + one :leave event fired")
      (is (= :enter (-> @events first first))
          "enter fired first")
      (is (= :leave (-> @events second first))
          "leave fired second"))))

(deftest send-message-merges-state-delta
  (testing "send-message merges :agent/state-delta into the runtime's state,
   threading the base state through correctly"
    (let [runtime (runtime/start {:exchange-chain (counter-chain)})]
      (is (= {} (user-state runtime))
          "fresh runtime has empty state")
      (runtime/send-message runtime "first")
      (is (= {:n 1} (user-state runtime))
          "after one send, state is {:n 1}")
      (runtime/send-message runtime "second")
      (is (= {:n 2} (user-state runtime))
          "after two sends, state is {:n 2} (the chain saw the prior state)")
      (runtime/send-message runtime "third")
      (is (= {:n 3} (user-state runtime))
          "after three sends, state is {:n 3}"))))

(deftest send-message-deep-merges-nested-state-delta
  (testing "nested maps in :agent/state-delta are merged deeply, while
   scalars remain last-write-wins across exchanges"
    (let [chain [{:name ::nested-delta
                  :leave (fn [ctx]
                           (let [turn (inc (:n (:agent/state ctx) 0))]
                             (assoc ctx :agent/state-delta
                                    {:n turn
                                     :config (case turn
                                               1 {:turn 1 :extra :one}
                                               2 {:turn 2}
                                               3 {:extra :three})})))}]
          runtime (runtime/start {:exchange-chain chain})]
      (runtime/send-message runtime "first")
      (is (= {:n 1 :config {:turn 1 :extra :one}} (user-state runtime)))
      (runtime/send-message runtime "second")
      (is (= {:n 2 :config {:turn 2 :extra :one}} (user-state runtime))
          "nested config map is merged, preserving sibling :extra from turn 1")
      (runtime/send-message runtime "third")
      (is (= {:n 3 :config {:turn 2 :extra :three}} (user-state runtime))
          "scalar :extra is last-write-wins; nested :turn keeps its prior value"))))

(deftest send-message-uses-custom-chain
  (testing "send-message runs the chain from :exchange-chain in agent-map"
    (let [events  (atom [])
          runtime (runtime/start {:exchange-chain (noop-chain events)})]
      (runtime/send-message runtime "hi")
      (is (= 2 (count @events))
          "the custom chain (not the default) ran"))))

(deftest send-message-puts-prior-state-on-ctx
  (testing "the per-exchange ctx carries :agent/state with the prior
   merged state, so the chain can read what the runtime has accumulated"
    (let [seen-states (atom [])
          chain       [{:name ::spy
                        :leave (fn [ctx]
                                 (swap! seen-states conj
                                        (dissoc (:agent/state ctx)
                                                :agent/session-id
                                                :agent/token-usage
                                                :agent/last-request-messages))
                                 (assoc ctx :agent/state-delta
                                        {:calls (count @seen-states)}))}]
          runtime     (runtime/start {:exchange-chain chain})]
      (runtime/send-message runtime "first")
      (is (= [{}] @seen-states)
          "the first exchange's ctx has the empty prior state")
      (runtime/send-message runtime "second")
      (is (= [{} {:calls 1}] @seen-states)
          "the second exchange's ctx has the post-first-send state"))))

(deftest send-message-returns-final-ctx
  (testing "send-message returns the final ctx from chain/execute"
    (let [chain   [{:name ::annotate
                    :leave (fn [ctx] (assoc ctx :marker true))}]
          runtime (runtime/start {:exchange-chain chain})
          result  (runtime/send-message runtime "hi")]
      (is (true? (:marker result))
          "the result carries the marker the chain put on it"))))

(deftest send-message-handles-chain-error
  (testing "when a chain stage throws and error-boundary is in the chain,
   send-message does not let the exception escape. The final ctx
   carries :error/raised and the runtime is still usable."
    (let [runtime  (runtime/start {:exchange-chain (throwing-chain)})
          result-1 (try
                     (runtime/send-message runtime "boom")
                     :ok
                     (catch Throwable _t
                       :threw))]
      (is (= :ok result-1)
          "send-message does not throw; error-boundary catches the bomb")
      (is (= :ok (try
                   (runtime/send-message runtime "recover")
                   :ok
                   (catch Throwable _t
                     :threw)))
          "the runtime is still usable after a chain error"))))

(deftest stop-returns-current-state
  (testing "stop returns the current merged state"
    (let [runtime (runtime/start {:exchange-chain (echo-chain)})]
      (is (= {} (user-state runtime))
          "stop on a fresh runtime returns the empty state")
      (runtime/send-message runtime "x")
      (is (= {:n 1} (user-state runtime))
          "stop after one send returns the merged state"))))

(deftest send-message-prewires-dependencies-in-ctx
  (testing "send-message pre-wires :llm/client, :memory/backend and
   :embedder directly on the per-exchange ctx, so interceptors do not
   need to copy them."
    (let [llm-client     (reify Object)
          memory-backend (reify Object)
          embedder       (reify Object)
          seen-ctx       (atom nil)
          spy-chain      [{:name ::spy
                           :enter (fn [ctx]
                                    (reset! seen-ctx ctx)
                                    (assoc ctx :agent/state-delta {:spied? true}))}]
          runtime        (runtime/start
                          {:exchange-chain    spy-chain
                           :agent/llm-client  llm-client
                           :memory-backend    memory-backend
                           :embedder          embedder})]
      (runtime/send-message runtime "hi")
      (is (identical? llm-client (:llm/client @seen-ctx))
          "the agent-map's LLM client is on ctx as :llm/client")
      (is (identical? memory-backend (:memory/backend @seen-ctx))
          "the agent-map's memory backend is on ctx as :memory/backend")
      (is (identical? embedder (:embedder @seen-ctx))
          "the agent-map's embedder is on ctx as :embedder"))))

(deftest send-message-accumulates-token-usage
  (testing "usage from :llm/response is accumulated into :agent/token-usage"
    (let [responses (atom [{:model "stub/v0"
                            :choices [{:message {:content "first"}}]
                            :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}}
                           {:model "stub/v0"
                            :choices [{:message {:content "second"}}]
                            :usage {:prompt_tokens 3 :completion_tokens 2 :total_tokens 5}}])
          chain     [{:name ::fake-llm
                      :enter (fn [ctx]
                               (let [resp (first @responses)]
                                 (swap! responses rest)
                                 (assoc ctx :llm/response resp)))}]
          runtime   (runtime/start {:exchange-chain chain})]
      (runtime/send-message runtime "hi")
      (is (= {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}
             (:agent/token-usage (runtime/stop runtime)))
          "after one exchange, usage equals the first response")
      (runtime/send-message runtime "again")
      (is (= {:prompt_tokens 13 :completion_tokens 7 :total_tokens 20}
             (:agent/token-usage (runtime/stop runtime)))
          "after two exchanges, usage is cumulative"))))

(deftest send-message-accumulates-token-usage-when-missing
  (testing "when the LLM response omits :usage, cumulative usage stays at zero"
    (let [chain   [{:name ::fake-llm
                    :enter (fn [ctx]
                             (assoc ctx :llm/response
                                    {:model "stub/v0"
                                     :choices [{:message {:content "hi"}}]}))}]
          runtime (runtime/start {:exchange-chain chain})]
      (runtime/send-message runtime "hi")
      (is (= {:prompt_tokens 0 :completion_tokens 0 :total_tokens 0}
             (:agent/token-usage (runtime/stop runtime)))))))

(deftest runtime-is-small
  (testing "the runtime ns is small (plan verification: < 150 LOC)"
    (let [lines (-> "src/kschltz/agent/runtime.clj"
                    slurp
                    str/split-lines
                    count)]
      (is (< lines 150)
          (str "runtime.clj is " lines " lines; plan requires < 150")))))

;; ============================================================================
;; Summarizer regression tests (cross-exchange)
;; ----------------------------------------------------------------------------
;; These pin the end-to-end behavior of the :history-summarize slot:
;; after the summarizer fires on a long history, exactly one
;; [Conversation Summary] system message persists across subsequent
;; exchanges, the protected window (incl. :role tool messages) is
;; preserved verbatim, and the next exchange's :llm/request carries
;; both the summary and the protected turns.
;; ============================================================================

(defn- summary-text-choice
  "OpenAI-shaped response carrying plain assistant `content`. Used both
   for normal exchange turns and for the summarizer's summary call."
  [content]
  {:choices [{:message {:role "assistant" :content content}}]
   :model   "fake/v0"})

(defn- summary-tool-call-choice
  "OpenAI-shaped response with one tool call (empty assistant content)."
  [id name args]
  {:choices [{:message {:role "assistant"
                        :content ""
                        :tool_calls [{:id id :type "function"
                                      :function {:name name :arguments args}}]}}]
   :model   "fake/v0"})

(defn- sum-scripted-llm
  "LlmClient that pops response maps from `queue-atom`. When the queue
   is empty, returns a default text response. The atom is the only
   mutable seam."
  [queue-atom]
  (reify LlmClient
    (-call [_ _req]
      (let [next (first @queue-atom)]
        (swap! queue-atom rest)
        (or next
            {:choices [{:message {:role "assistant" :content "done"}}]
             :model   "fake/v0"})))))

(defn- summary-msg?
  "Return `m` when it is a system message emitted by the summarizer,
   else nil. Truthy as a predicate; returns the map so callers can
   thread `:content` off it."
  [m]
  (when (and (map? m)
              (= "system" (:role m))
              (str/starts-with? (or (:content m) "")
                                "[Conversation Summary - generated"))
    m))

(defn- count-summaries [history]
  (count (filter summary-msg? history)))

(defn- seed-pairs
  "Return a vector of `n` user/assistant pairs (2n messages)."
  [n]
  (vec (mapcat (fn [i]
                 [{:role "user"      :content (str "seed-q-" i)}
                  {:role "assistant" :content (str "seed-a-" i)}])
               (range n))))

(defn- summary-agent-map
  "Assemble a base + summarizer (+ tools) chain into a runtime-ready
   agent-map. The SAME scripted LlmClient is wired as :agent/llm-client
   and into the summarizer plugin so call order is deterministic."
  ([llm summarizer-opts]
   (summary-agent-map llm summarizer-opts nil))
  ([llm summarizer-opts registry]
   (let [chain (plugin/assemble-chain
                (cond-> [(plugins.base/base-plugin)
                         (plugins.summarizer/summarizer-plugin
                          (assoc summarizer-opts :llm-client llm))]
                  registry (conj (plugins.tools/tools-plugin registry))))]
     {:agent/llm-client llm
      :exchange-chain   chain
      :initial-state    {:base-url             "stub"
                         :api-key              nil
                         :model                "fake/v0"
                         :agent/system-message "You have tools."}})))

;; ---- Echo tool for the tool-result protection variant ----

(deftype SummaryEchoTool []
  tool/Tool
  (-name [_] "echo")
  (-description [_] "Echoes the message back.")
  (-input-schema [_] [:map [:msg :string]])
  (-output-schema [_] :string)
  (-invoke [_ args _ctx] (:msg args)))

(deftest cross-exchange-summary-survives
  (testing "after the summarizer fires on a long history, exactly one
            [Conversation Summary] system message persists across
            subsequent exchanges and is visible to a later exchange's
            :llm/request; the latest user/assistant turns stay verbatim"
    (let [;; Seed 100 messages (50 pairs) so the first exchange pushes
            ;; the non-system body to 102 (> trigger 60) and fires the
            ;; summarizer exactly once. Subsequent text exchanges append
            ;; 2 messages each and stay under the trigger, so no second
            ;; summary is produced.
          queue       (atom [(summary-text-choice "a1")
                             (summary-text-choice "SUMMARY-OF-SEED")
                             (summary-text-choice "a2")
                             (summary-text-choice "a3")])
          llm         (sum-scripted-llm queue)
          agent-map   (summary-agent-map
                       llm {:trigger 60 :protected-pairs 10 :max-history 100
                            :model "fake/v0"})
          rt          (runtime/start
                      (assoc agent-map
                             :initial-state
                             (assoc (:initial-state agent-map)
                                    :agent/history (seed-pairs 50)))
                      "summary-session")
          _out1       (runtime/send-message rt "q1")
          _out2       (runtime/send-message rt "q2")
          out3        (runtime/send-message rt "q3")
          final       (runtime/stop rt)
          history     (:agent/history final)
          req-msgs    (-> out3 :llm/request :messages)]
      ;; Exactly one summary system message in the persisted history.
      (is (= 1 (count-summaries history))
          (str "history holds exactly one summary message; got "
               (pr-str (mapv #(select-keys % [:role :content]) history))))
      ;; The summary text came from the stub LlmClient.
      (is (some #(str/includes? (:content % "") "SUMMARY-OF-SEED") history)
          "the persisted summary carries the stub LlmClient's text")
      ;; The summary survived across exchanges 2 and 3: exchange 3's
      ;; outgoing LLM request messages include the summary system
      ;; message (compose-context read it from :agent/history).
      (is (some summary-msg? req-msgs)
          (str "exchange 3 request carries the summary; got msgs="
               (pr-str (mapv #(select-keys % [:role :content]) req-msgs))))
      ;; The latest user/assistant turns are present verbatim.
      (is (some #(= "q3" (:content %)) history)
          "the latest user turn is present verbatim")
      (is (some #(= "a3" (:content %)) history)
          "the latest assistant turn is present verbatim")
      ;; The summary precedes the recent turns in the request.
      (is (some (fn [[i m]]
                  (and (summary-msg? m)
                       (some #(= "q3" (:content %))
                             (drop (inc i) req-msgs))))
                (map-indexed vector req-msgs))
          "the summary precedes the latest user turn in the request")
      ;; The oldest seed turn was replaced by the summary (not still
      ;; present verbatim).
      (is (not-any? #(= "seed-q-0" (:content %)) history)
          "the oldest seed turn was summarized away"))))

(deftest summarization-does-not-drop-tool-results
  (testing "when summarization fires after a tool-calling exchange,
            the protected :role tool result message (and its matching
            assistant :tool_calls turn) survive in :agent/history and in
            the next exchange's :llm/request :messages"
    (let [;; q1 / q2 are plain text (1 LLM call each). q3 drives a tool
            ;; call: tool_call -> empty-text (forces summary path) ->
            ;; final text. After store-exchange appends 4 messages the
            ;; non-system body is 8 (> trigger 6) so the summarizer
            ;; fires once with the tool pair inside the 4-message
            ;; protected window. q4 is plain text; it does NOT re-trigger
            ;; the summarizer.
          queue       (atom [(summary-text-choice "a1")
                             (summary-text-choice "a2")
                             (summary-tool-call-choice "tc3" "echo" "{\"msg\":\"hi\"}")
                             (summary-text-choice "")
                             (summary-text-choice "got it: hi")
                             (summary-text-choice "SUMMARY-OF-OLD")
                             (summary-text-choice "a4")])
          llm         (sum-scripted-llm queue)
          agent-map   (summary-agent-map
                       llm {:trigger 6 :protected-pairs 2 :max-history 100
                            :model "fake/v0"}
                       {"echo" (->SummaryEchoTool)})
          rt          (runtime/start agent-map "summary-tool-session")
          _out1       (runtime/send-message rt "q1")
          _out2       (runtime/send-message rt "q2")
          _out3       (runtime/send-message rt "call echo with hi")
          out4        (runtime/send-message rt "q4")
          final       (runtime/stop rt)
          history     (:agent/history final)
          req-msgs    (-> out4 :llm/request :messages)]
      ;; Sanity: exchange 3 actually ran the echo tool.
      (is (= "got it: hi" (:exchange/response _out3))
          "exchange 3 reached the final text via ensure-text-response")
      ;; Exactly one summary message persisted.
      (is (= 1 (count-summaries history))
          "exactly one summary system message is present")
      ;; The protected tool result survived summarization.
      (is (some #(and (= "tool" (:role %))
                      (= "tc3" (:tool_call_id %))
                      (= "hi" (:content %))) history)
          (str "the protected tool result for tc3 survived summarization; "
               "got history=" (pr-str history)))
      ;; The matching assistant tool_calls turn survived too (no orphan).
      (is (some #(and (= "assistant" (:role %))
                      (-> % :tool_calls first :id (= "tc3"))) history)
          "the assistant tool_calls turn for tc3 survived (no orphan)")
      ;; The tool result is visible to the next exchange's LLM request.
      (is (some #(and (= "tool" (:role %))
                      (= "tc3" (:tool_call_id %))) req-msgs)
          (str "exchange 4 request includes the persisted tool message; "
               "got msgs="
               (pr-str (mapv #(select-keys % [:role :tool_call_id :content])
                             req-msgs))))
      ;; The summary is also in the next exchange's request, preceding
      ;; the protected window.
      (is (some summary-msg? req-msgs)
          "exchange 4 request includes the summary system message")
      ;; The latest turns are present verbatim.
      (is (some #(= "q4" (:content %)) history)
          "the latest user turn is present verbatim")
      (is (some #(= "a4" (:content %)) history)
          "the latest assistant turn is present verbatim"))))