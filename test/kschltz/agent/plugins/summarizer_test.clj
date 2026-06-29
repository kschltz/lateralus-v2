(ns kschltz.agent.plugins.summarizer-test
  "Unit tests for the history-summarizer plugin.

   The summarizer compresses long `:agent/history` vectors so context
   growth stays bounded. It runs as a `:leave` interceptor that:

     1. reads `:agent/history` from `:agent/state-delta` (what
        `store-exchange` just wrote this exchange), falling back to
        `:agent/state :agent/history`;
     2. separates the leading original system message (if any and NOT
        itself a prior summary) from the non-system body;
     3. when the non-system body length exceeds `:trigger`, splits the
        body into `[oldest-block protected-window]` where the protected
        window is the most-recent `(* :protected-pairs 2)` non-system
        messages (aligned so a `:role assistant :tool_calls` /
        `:role tool` pair is never split);
     4. calls the configured `LlmClient` with a summary request built
        from the oldest block;
     5. emits a SINGLE `{:role \"system\" :content
        \"[Conversation Summary - generated <ts>]\\n<summary>\"}`
        message and overwrites `:agent/history` in
        `:agent/state-delta` with `[<leading-system?> summary-msg
        ...protected-window]` routed through `trim-history`.

   These tests drive the pure helpers and the leave fn directly with a
   stub `LlmClient` (queue-atom / call-counter pattern). No network.
   The plugin constructor is asserted separately.

   NOTE for the implementer: the summarizer's :leave MUST run AFTER
   `store-exchange`'s :leave. The chain engine runs :leave in reverse
   stack order (see `kschltz.agent.chain/execute`), so the summarizer
   must ENTER before `store-exchange` — i.e. occupy a slot with a
   LOWER rank than `:history`. The unit tests here call the leave fn
   directly (ctx already carries the post-store-exchange history in
   `:agent/state-delta`), so they pass regardless of slot mechanics;
   the runtime-test ns asserts the end-to-end ordering."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.llm.client :refer [LlmClient]]
            [kschltz.agent.plugins.summarizer :as plugins.summarizer]))

;; ---- Synthetic history builders ----

(defn- pair-seq
  "Return a vector of `n` user/assistant turn pairs with `prefix`-tagged
   content. `n` pairs => `(* 2 n)` non-system messages."
  ([n prefix] (pair-seq n prefix 0))
  ([n prefix start]
   (vec (mapcat (fn [i]
                  [{:role "user"      :content (str prefix "-q-" i)}
                   {:role "assistant" :content (str prefix "-a-" i)}])
                (range start (+ start n))))))

(defn- tool-pair
  "Return a well-formed OpenAI tool-call pair: an assistant turn
   carrying `:tool_calls` followed by a `:role tool` result for
   `tool-call-id`."
  [tool-call-id content]
  [{:role       "assistant"
    :content    ""
    :tool_calls [{:id tool-call-id :type "function"
                  :function {:name "echo" :arguments "{\"msg\":\"hi\"}"}}]}
   {:role         "tool"
    :tool_call_id tool-call-id
    :content      content}])

(defn- summary-msg?
  "Return `m` when it is a system message emitted by the summarizer,
   else nil. Truthy as a predicate (so `(some summary-msg? coll)` and
   `(filter summary-msg? coll)` work) but returns the map itself so
   callers can thread it through `:content` without an extra pass."
  [m]
  (when (and (map? m)
              (= "system" (:role m))
              (str/starts-with? (or (:content m) "")
                                "[Conversation Summary - generated"))
    m))

(defn- count-summary-messages [history]
  (count (filter summary-msg? history)))

(defn- stub-llm
  "LlmClient that always returns `content` and records call count in
   `calls-atom`. Pops from `queue-atom` when supplied so each call can
   return a different response.

   Two-arity form: 2-arg (calls-atom content-or-atom):
   - if `content-or-atom` is a string, build a single-response queue
     with that content;
   - if `content-or-atom` is an atom, use it as the response queue."
  [calls-atom content-or-atom]
  (let [queue-atom (if (string? content-or-atom)
                     (atom [{:choices [{:message {:role "assistant"
                                                   :content content-or-atom}}]
                             :model   "fake/v0"}])
                     content-or-atom)]
    (reify LlmClient
      (-call [_ _req]
        (swap! calls-atom inc)
        (let [next (first @queue-atom)]
          (swap! queue-atom rest)
          (or next
              {:choices [{:message {:role "assistant" :content "default"}}]
               :model   "fake/v0"}))))))

(defn- run-leave
  "Invoke the summarizer :leave fn on a ctx whose state-delta already
   carries `history` (simulating the post-store-exchange state)."
  [ix history]
  ((:leave ix)
   {:agent/state        {:agent/history history}
    :agent/state-delta  {:agent/history history}
    :exchange/session-id :summarizer-test
    :exchange/user-msg-id "u"
    :exchange/assistant-msg-id "a"}))

;; ---- Plugin construction ----

(deftest summarizer-plugin-construction
  (testing "the plugin has the expected name and a single :history-summarize interceptor"
    (let [llm (stub-llm (atom 0) "x")
          p   (plugins.summarizer/summarizer-plugin {:llm-client llm})]
      (is (= :summarizer (-> p meta :plugin/name))
          "plugin metadata carries :plugin/name :summarizer")
      (is (= 1 (count p)) "plugin contributes exactly one interceptor")
      (let [ix (first p)]
        (is (= :history-summarize (:slot ix))
            "interceptor is tagged for the :history-summarize slot")
        (is (= ::ix/summarize-history (:name ix))
            "interceptor name is ::summarize-history")
        (is (fn? (:leave ix)) "interceptor has a :leave fn")
        (is (nil? (:enter ix)) "interceptor has no :enter fn")))))

(deftest summarizer-plugin-default-opts
  (testing "default trigger/max-history/protected-pairs match the interceptor constants"
    (let [llm (stub-llm (atom 0) "x")
          p   (plugins.summarizer/summarizer-plugin {:llm-client llm})
          ix  (first p)]
      (is (fn? (:leave ix)))
      ;; Defaults must agree with the public interceptor constants so
      ;; production config and the plugin stay in sync.
      (is (= 60 ix/summarize-trigger))
      (is (= 100 ix/max-history-entries))
      (is (= 10 ix/protected-turn-pairs)))))

;; ---- Pure helpers ----

(deftest split-protected-window-keeps-last-n-pairs
  (testing "split-protected-window returns [oldest-block protected-window]
            with the protected window = last (* protected-pairs 2)
            non-system messages"
    (let [body (pair-seq 40 "m")            ; 80 non-system messages
          [oldest protected] (ix/split-protected-window body 10)]
      (is (= 60 (count oldest)) "oldest block holds everything before the window")
      (is (= 20 (count protected)) "protected window is 20 messages (10 pairs)")
      (is (= (subvec body 60) protected)
          "protected window is exactly the trailing 20 messages")
      (is (= (subvec body 0 60) oldest)
          "oldest block is exactly the leading 60 messages"))))

(deftest split-protected-window-small-body-returns-empty-oldest
  (testing "when the body is smaller than the protected window, the
            oldest block is empty and the whole body is protected"
    (let [body (pair-seq 3 "m")             ; 6 non-system messages
          [oldest protected] (ix/split-protected-window body 10)]
      (is (empty? oldest) "oldest block is empty when body fits the window")
      (is (= body protected) "the whole body is the protected window"))))

(deftest split-protected-window-never-orphans-a-tool-result
  (testing "the protected window never starts with a :role tool message,
            so an assistant tool_calls / tool result pair is never split"
    (let [;; well-formed body: pair 9 is a tool pair at the tail
          body (into (pair-seq 9 "m") (tool-pair "tc9" "hi")) ; 18 + 2 = 20
          [oldest protected] (ix/split-protected-window body 10)]
      (is (= 20 (count protected))
          "protected window is the full 20 (body exactly fits the window)")
      (is (not= "tool" (:role (first protected)))
          "protected window does not start with a :role tool message")
      ;; Construct a body where the natural 20-window boundary lands ON
      ;; a :role tool message (malformed tail) and assert the split
      ;; extends back so the protected window still does not start with
      ;; a tool message.
      (let [bad-body (into (pair-seq 10 "m")
                           [{:role "user" :content "x"}
                            {:role "tool" :tool_call_id "tcX" :content "orphan"}]) ; 20 + 2 = 22
            [_ protected2] (ix/split-protected-window bad-body 10)]
        (is (not= "tool" (:role (first protected2)))
            "split protects against an orphaned tool message at the boundary")))))

(deftest build-summary-request-shape
  (testing "build-summary-request yields a request whose messages are the
            oldest block followed by a user summarize instruction"
    (let [oldest (pair-seq 5 "m")
          req    (ix/build-summary-request oldest "cheap/v0")]
      (is (= "cheap/v0" (:model req)) "request carries the model")
      (is (= (vec oldest) (subvec (:messages req) 0 (count oldest)))
          "the request messages start with the oldest block verbatim")
      (is (= "user" (:role (peek (:messages req))))
          "last message is the summarize instruction")
      (is (str/includes? (str/lower-case (:content (peek (:messages req))))
                         "summari")
          "the instruction asks for a summary"))))

(deftest build-summary-message-shape
  (testing "build-summary-message produces a single :role system message
            with the [Conversation Summary - generated <ts>] prefix"
    (let [msg (ix/build-summary-message "the chat was about X" 1700000000000)]
      (is (= "system" (:role msg)))
      (is (= (str "[Conversation Summary - generated 1700000000000]\n"
                  "the chat was about X")
             (:content msg))
          "content is the prefix header followed by a newline and the summary"))))

;; ---- Leave fn behavior ----

(deftest summarizer-noop-when-history-small
  (testing "a history under the trigger is returned unchanged and the
            LlmClient is never called"
    (let [calls   (atom 0)
          llm     (stub-llm calls "SHOULD-NOT-BE-USED")
          ix      (ix/summarize-history {:llm-client llm :trigger 60
                                          :protected-pairs 10 :model "fake/v0"})
          history (into [{:role "system" :content "SYS"}]
                        (pair-seq 25 "m"))   ; 50 non-system < 60
          result  (run-leave ix history)]
      (is (zero? @calls) "LlmClient was not called")
      (is (= history (get-in result [:agent/state-delta :agent/history]))
          "history in state-delta is byte-for-byte unchanged")
      (is (zero? (count-summary-messages
                  (get-in result [:agent/state-delta :agent/history])))
          "no summary message was emitted"))))

(deftest summarizer-fires-when-history-large
  (testing "a history over the trigger calls the LlmClient once and
            replaces the oldest block with a single summary message"
    (let [calls   (atom 0)
          llm     (stub-llm calls "SUMMARY-OF-OLD")
          ix      (ix/summarize-history {:llm-client llm :trigger 60
                                          :protected-pairs 10 :model "fake/v0"})
          body    (pair-seq 40 "m")           ; 80 non-system > 60
          history (into [{:role "system" :content "SYS"}] body)
          result  (run-leave ix history)
          out     (get-in result [:agent/state-delta :agent/history])]
      (is (= 1 @calls) "LlmClient was called exactly once")
      (is (< (count out) (count history))
          "resulting history is shorter than the input")
      (is (= 1 (count-summary-messages out))
          "exactly one summary system message is present")
      (is (str/includes? (:content (some summary-msg? out)) "SUMMARY-OF-OLD")
          "the summary text came from the stub LlmClient"))))

(deftest summarizer-preserves-system-message
  (testing "the leading original system message stays at position 0 of
            the summarized history"
    (let [llm     (stub-llm (atom 0) "S")
          ix      (ix/summarize-history {:llm-client llm :trigger 60
                                          :protected-pairs 10 :model "fake/v0"})
          history (into [{:role "system" :content "SYS"}]
                        (pair-seq 40 "m"))
          out     (get-in (run-leave ix history)
                          [:agent/state-delta :agent/history])]
      (is (= {:role "system" :content "SYS"} (first out))
          "the leading system message is preserved at index 0")
      (is (summary-msg? (second out))
          "the summary message sits at index 1, right after the system message"))))

(deftest summarizer-preserves-recent-turns
  (testing "the last 10 turn pairs are kept verbatim and the older turns
            are replaced by the summary"
    (let [llm     (stub-llm (atom 0) "OLD")
          ix      (ix/summarize-history {:llm-client llm :trigger 60
                                          :protected-pairs 10 :model "fake/v0"})
          body    (pair-seq 40 "m")           ; 80 non-system; last 20 protected
          history (into [{:role "system" :content "SYS"}] body)
          out     (get-in (run-leave ix history)
                          [:agent/state-delta :agent/history])
          ;; out = [SYS, summary, <20 protected = body[60..80]>]
          protected (subvec out 2)]
      (is (= (subvec body 60) protected)
          "the last 20 messages (10 turn pairs) are kept verbatim and in order")
      (is (empty? (filter #(str/starts-with? (:content % "m") "m-q-0") protected))
          "the oldest turn (m-q-0) is NOT in the protected window")
      (is (not-any? #(= "m-q-0" (:content %)) out)
          "the oldest turn has been replaced by the summary"))))

(deftest summarizer-anchor-on-most-recent-user
  (testing "the most recent :role user turn is never dropped by summarization"
    (let [llm     (stub-llm (atom 0) "OLD")
          ix      (ix/summarize-history {:llm-client llm :trigger 60
                                          :protected-pairs 10 :model "fake/v0"})
          ;; 40 pairs but the final user turn is uniquely tagged so we
          ;; can assert it survives summarization.
          body    (assoc (pair-seq 40 "m")
                         78 {:role "user" :content "ANCHOR-USER"})
          history (into [{:role "system" :content "SYS"}] body)
          out     (get-in (run-leave ix history)
                          [:agent/state-delta :agent/history])]
      (is (some #(= "ANCHOR-USER" (:content %)) out)
          "the most recent user turn survives summarization")
      (is (some #(and (= "user" (:role %))
                      (= "ANCHOR-USER" (:content %))) out)
          "the anchored user turn retains its :role user"))))

(deftest summarizer-writes-to-state-delta
  (testing "the leave fn overwrites :agent/history in :agent/state-delta
            with the summarized vector (last-write-wins for vectors)"
    (let [llm     (stub-llm (atom 0) "S")
          ix      (ix/summarize-history {:llm-client llm :trigger 60
                                          :protected-pairs 10 :model "fake/v0"})
          history (into [{:role "system" :content "SYS"}]
                        (pair-seq 40 "m"))
          result  (run-leave ix history)
          delta   (:agent/state-delta result)]
      (is (map? delta) "state-delta is a map")
      (is (contains? delta :agent/history)
          "state-delta carries the rewritten :agent/history")
      (is (= 1 (count-summary-messages (:agent/history delta)))
          "the rewritten history contains exactly one summary message"))))

(deftest summarizer-stub-client-output-shape
  (testing "the emitted summary message content includes the
            [Conversation Summary - generated marker"
    (let [llm     (stub-llm (atom 0) "the model summarized the old turns")
          ix      (ix/summarize-history {:llm-client llm :trigger 60
                                          :protected-pairs 10 :model "fake/v0"})
          history (into [{:role "system" :content "SYS"}]
                        (pair-seq 40 "m"))
          out     (get-in (run-leave ix history)
                          [:agent/state-delta :agent/history])
          sm      (some summary-msg? out)]
      (is (some? sm) "a summary message was emitted")
      (is (str/starts-with? (:content sm)
                            "[Conversation Summary - generated")
          "summary content begins with the marker prefix")
      (is (str/includes? (:content sm) "the model summarized the old turns")
          "summary content carries the stub LlmClient's text after the header"))))

(deftest summarizer-tool-results-in-protected-window-survive
  (testing "a :role tool message that falls inside the protected window
            is preserved with its :tool_call_id and :content"
    (let [llm     (stub-llm (atom 0) "OLD")
          ix      (ix/summarize-history {:llm-client llm :trigger 6
                                          :protected-pairs 2 :model "fake/v0"})
          ;; 4 pairs (8 msgs) + a tool pair at the tail (2 msgs) = 10
          ;; non-system; trigger 6 fires; protected = last 4 = the
          ;; user turn + the tool pair + the final assistant.
          body    (into (pair-seq 4 "m")
                       (into [{:role "user" :content "q"}]
                             (tool-pair "tcN" "tool-result")))
          history body                          ; no leading system on purpose
          out    (get-in (run-leave ix history)
                         [:agent/state-delta :agent/history])]
      (is (= 1 (count-summary-messages out)))
      (is (some #(and (= "tool" (:role %))
                      (= "tcN" (:tool_call_id %))
                      (= "tool-result" (:content %))) out)
          "the protected tool result message survives summarization")
      (is (some #(and (= "assistant" (:role %))
                      (seq (:tool_calls %))) out)
          "the matching assistant tool_calls turn also survives"))))