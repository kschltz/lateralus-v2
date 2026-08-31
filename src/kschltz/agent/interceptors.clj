 (ns kschltz.agent.interceptors
    "v2 interceptor stages for the agent exchange pipeline.

   Each stage is a plain map satisfying
   `kschltz.agent.interceptors.schema/Interceptor`.

   v2 design notes:
   - **No** delegation to `kschltz.agent.loop` (forbidden by plan;
     verify with `rg 'agent\\.loop' src/`).
   - **No** direct LLM HTTP calls. All provider I/O goes through
     the `LlmClient` protocol (Step 5); MVP ships with a stub
     implementation.
   - **No** tool execution. MVP default tool registry is empty;
     `dispatch` records the (empty) results vector on ctx.
   - Stages are pure functions of ctx. They return a new ctx and
     never mutate shared refs.

   Stages defined here (in execution order; see `default-exchange-chain`):
     error-boundary   — :error handler that handles the error (clears
                        engine ::error, annotates :error/raised) so
                        subsequent :leave stages run and the
                        annotation is observable on the final ctx
     compose-context  — assembles the LLM request from :agent/state +
                        :exchange/user-text + recall
     llm-call         — invokes the configured LlmClient; protocol-only
     parse-response   — extracts :exchange/response and :tool/calls
     dispatch         — records :tool/results (sequential mapv); MVP
                        has no real tools; see `dispatch-test`
     store-exchange   — leave stage; records the final exchange
     deliver-responses — leave stage; hands responses to listeners
     notify           — leave stage; fires on-thought / on-response"
   (:require [cheshire.core :as json]
             [clojure.string :as str]
              [kschltz.agent.chain :as chain]
              [kschltz.agent.interceptors.schema :as schema]
              [kschltz.agent.llm.client :as llm-client]
              [kschltz.agent.llm.schemas :as schemas]
              [malli.core :as m]))

;; ---- LlmClient comes from `kschltz.agent.llm.client` (canonical).
;;      The interceptor namespace only knows the protocol boundary;
;;      Step 5 wires the real HTTP impl behind it.

(defn default-llm-client
  "MVP stub LlmClient. Returns a deterministic text response. Step 5
   replaces this with the real HTTP-backed implementation."
  []
  (llm-client/stub-client))

(defn call-llm
  "Invoke the LlmClient on ctx. Reads `:llm/client` (pre-wired by
   the runtime from the agent map). Falls back to the stub only
   when no client is present, preserving the legacy test path.
   Reads `:llm/request`. Writes `:llm/response`."
  [ctx]
  (let [client (or (:llm/client ctx) (default-llm-client))
        req    (:llm/request ctx)]
    ;; #region agent log
    (spit "/opt/cursor/logs/debug.log"
          (str (json/generate-string
                {:hypothesisId "A,B"
                 :location "interceptors.clj:call-llm:before"
                 :message "calling LlmClient protocol"
                 :data {:model (:model req)
                        :messageCount (count (:messages req))
                        :toolCount (count (:tools req))
                        :lastRole (:role (peek (:messages req)))
                        :lastToolNames (mapv #(get-in % [:function :name])
                                             (:tool_calls (peek (:messages req))))}
                 :timestamp (System/currentTimeMillis)})
               "\n")
          :append true)
    ;; #endregion
    (try
      (let [response (llm-client/-call client req)]
        ;; #region agent log
        (spit "/opt/cursor/logs/debug.log"
              (str (json/generate-string
                    {:hypothesisId "A"
                     :location "interceptors.clj:call-llm:after"
                     :message "LlmClient protocol returned"
                     :data {:responseKeys (mapv str (keys response))
                            :finishReason (get-in response [:choices 0 :finish_reason])
                            :toolNames (mapv #(get-in % [:function :name])
                                             (get-in response
                                                     [:choices 0 :message :tool_calls]))}
                     :timestamp (System/currentTimeMillis)})
                   "\n")
              :append true)
        ;; #endregion
        (assoc ctx :llm/response response))
      (catch Throwable t
        ;; #region agent log
        (spit "/opt/cursor/logs/debug.log"
              (str (json/generate-string
                    {:hypothesisId "A"
                     :location "interceptors.clj:call-llm:error"
                     :message "LlmClient protocol threw"
                     :data {:exceptionClass (.getName (class t))
                            :hasMessage (boolean (some-> t ex-message seq))
                            :safeErrorData (select-keys (ex-data t)
                                                       [:kind :status :phase])}
                     :timestamp (System/currentTimeMillis)})
                   "\n")
              :append true)
        ;; #endregion
        (throw t)))))

;; ---- Stage definitions ----

(defn- response-text
  "Extract assistant text from a response map. Tolerates stubs by
   defaulting to the empty string."
  [response]
  (or (get-in response [:choices 0 :message :content]) ""))

(defn- response-tool-calls
  "Extract tool calls from a response. MVP stub returns no calls."
  [response]
  (or (get-in response [:choices 0 :message :tool_calls]) []))

(defn- response-thinking
  "Extract provider reasoning text, or nil when absent/blank."
  [response]
  (schemas/extract-thinking response))
;; TODO memory-followup: delete this stub. It is a no-op marker
;; so a future history-trimming PR has a clear `find-fn + replace`
;; target (token budget, recall window, etc.).
(declare trim-history)  ;; forward-declared so the summarizer helpers
                        ;; below can call it

(def max-history-entries
  "Hard cap on the number of non-system messages retained in
   `:agent/history` after trimming. Keeps context growth bounded so
   large file reads do not blow up the request forever. Bumped from
   40 → 100 in 2026-06 to give the summarize-history interceptor
   room to compact long sessions instead of silently dropping the
   oldest turns."
  100)

(def protected-turn-pairs
  "Number of most-recent turn pairs the summarizer preserves verbatim
   above the older block it compresses. A 'turn pair' is one user
   message plus the assistant/tool messages that follow it. Defaults
   to 10 — chosen so the protected window covers roughly the last
   10 user turns of the conversation."
  10)

(def summarize-trigger
  "Threshold (non-system message count) at which the summarize-history
   interceptor fires. Below this, no summarization happens and
   `trim-history` carries the cap. At or above this, the oldest block
   above the protected window is replaced with a single
   `[Conversation Summary - generated <ts>]` system message.

   Picked to leave ~40 messages of headroom before the hard
   `max-history-entries` cap (100), so a single summarization event
   returns the history well below the cap and the next few exchanges
   do not retrigger."
  60)

(def ^:private max-tool-content-chars
  "Hard cap on the `:content` of any `:role tool` message retained
   in history. Tool results larger than this are truncated with a
   `...[trimmed]` marker so the model still knows the original was
   larger but does not pay the token cost on every subsequent turn.
   This is the DEFAULT cap; a per-tool override map
   (`:tool-content-caps` in `:lateralus/loop-opts`) can raise it for
   tools whose results are structurally large and must survive intact
  (e.g. `clojure_eval` / `clojure_add_lib` Clerk render traces) — see
   `truncate-tool-content`."
  2000)

(defn truncate-tool-content
  "Truncate a single message's `:content` if it is a `:role tool`
   message whose content exceeds its cap. Non-tool messages (and tool
   messages already under the cap) pass through unchanged. The
   truncation appends a `...[trimmed]` marker so the model can see it
   was elided, not silently corrupted.

   The cap is selected per message: when `caps` (a map of tool-name ->
   int) is supplied AND the message carries a `:name` (the tool name,
   stamped onto tool-result messages by the loop), the per-tool cap is
   used; otherwise `max-tool-content-chars` (the default 2000) applies.
  This lets `clojure_eval` / `clojure_add_lib` keep their (structurally
   large) Clerk render traces intact while every other tool result is
   still bounded by the default cap (audit 2026-07 rec #5: the old
   fixed 2000-char cap silently destroyed Clerk's >2KB output, so the
   model re-evaluated the same oversize code and stalled)."
  ([msg] (truncate-tool-content msg nil))
  ([msg caps]
   (let [content (:content msg)
         cap     (if-let [name (:name msg)]
                   (or (get caps name) max-tool-content-chars)
                   max-tool-content-chars)
         over?   (and (= "tool" (:role msg))
                     (string? content)
                     (> (count content) cap))]
     (if over?
       (assoc msg :content
              (str (subs content 0 cap)
                   "...[trimmed]"))
       msg))))

;; ---- Summarization helpers --------------------------------------------
;;
;; The summarizer plugin (`kschltz.agent.plugins.summarizer`) calls
;; these pure helpers and the `summarize-history` factory. Exposed at
;; the top level so tests and callers can drive each piece in isolation.

(def ^:private default-summarize-instruction
  "Summarize the following conversation for an AI agent. Preserve: user-stated goals, constraints, decisions made, facts discovered, errors resolved. Drop: pleasantries, redundant exploration. Be dense.")

(defn split-protected-window
  "Split `body` (non-system messages) into `[oldest-block
   protected-window]`. The protected window covers the trailing
   `protected-pairs` user turns and their following
   assistant/tool messages, so an `:role assistant :tool_calls` /
   `:role tool` pair is never split.

   The split is anchored so the protected window never starts with a
   `:role \"tool\"` message — if a malformed history has a tool
   message at the boundary, the window is extended back to include
   the matching assistant turn (or, failing that, the preceding
   user turn)."
  [body protected-pairs]
  (let [n        (count body)
        cut-idx
        (loop [i     (dec n)
               pairs 0]
          (cond
            (neg? i)             0
            (>= pairs protected-pairs)
            ;; have enough pairs; if the boundary lands on a tool
            ;; message, extend back so the window starts on a user
            ;; turn (which keeps the assistant tool_calls turn whole)
            (let [start (if (= "tool" (:role (nth body i)))
                          (loop [j i]
                            (cond
                              (neg? j) 0
                              (= "user"      (:role (nth body j))) (inc j)
                              (= "assistant" (:role (nth body j))) (inc j)
                              :else (recur (dec j))))
                          (inc i))]
              (min start n))
            (= "user" (:role (nth body i)))
            (recur (dec i) (inc pairs))
            :else (recur (dec i) pairs)))]
    (if (zero? cut-idx)
      [[] (vec body)]
      [(subvec body 0 cut-idx)
       (subvec body cut-idx)])))

(defn build-summary-request
  "Build the LlmClient request payload for one summarization call.
   `oldest` is the contiguous block to compress; `model` is the
   model name to set on the request."
  [oldest model]
  {:model    (or model "stub-summarizer")
   :messages (into (vec oldest)
                   [{:role "user" :content default-summarize-instruction}])})

(defn build-summary-message
  "Build the system-role marker message that replaces the summarized
   block. `summary-text` is the LlmClient's response; `ts` is the
   timestamp (ms since epoch)."
  [summary-text ts]
  {:role    "system"
   :content (str "[Conversation Summary - generated " ts "]\n" summary-text)})

(defn- now-ms []
  (System/currentTimeMillis))

(defn- history-body
  "Return `[leading-system body]` for a history vector. The leading
   system message (if any and not itself a prior summary) is kept
   separate; the body is the rest."
  [history]
  (let [hist      (vec history)
        first-    (first hist)
        is-sys?   (and (map? first-) (= "system" (:role first-)))
        is-summ?  (and is-sys?
                      (str/starts-with? (or (:content first-) "")
                                        "[Conversation Summary"))
        leading   (when (and is-sys? (not is-summ?)) first-)
        body      (cond
                    (and is-sys? (not is-summ?)) (subvec hist 1)
                    :else hist)]
    [leading body]))

(defn- call-summarizer-llm
  "Invoke the summarizer LlmClient on a request. Returns the assistant
   text from the response, or a placeholder string when the client is
   missing or the response is malformed."
  [client req]
  (try
    (let [resp (llm-client/-call client req)
          txt  (or (get-in resp [:choices 0 :message :content]) "")]
      (if (seq txt) txt "[summary unavailable]"))
    (catch Throwable _
      "[summary unavailable]")))

(defn summarize-history
  "Factory. Return an interceptor map (without `:name`/`:slot`) whose
   `:leave` fn compacts a long `:agent/history` into a single summary
   message plus a protected window of recent turns.

   Options:
     :llm-client      — required `LlmClient` instance
     :trigger         — non-system message count above which to fire
                        (default `summarize-trigger`)
     :protected-pairs — number of user turns to keep verbatim (default
                        `protected-turn-pairs`)
     :model           — model hint passed to the LlmClient (default
                        \"stub-summarizer\")

   The interceptor name and slot are added by the plugin; this fn
   returns only the stage fns."
  [{:keys [llm-client trigger protected-pairs model]
    :or   {trigger         summarize-trigger
           protected-pairs protected-turn-pairs
           model           "stub-summarizer"}}]
  {:leave
   (fn summarize-leave [ctx]
     (let [delta      (:agent/state-delta ctx)
           new-hist   (:agent/history delta)
           base-hist  (:agent/history (:agent/state ctx))
           hist       (cond
                        (vector? new-hist)  new-hist
                        (vector? base-hist) base-hist
                        :else               nil)
           [leading body] (history-body hist)
           body-len      (count body)
           [oldest protected] (split-protected-window body protected-pairs)]
       (if (and (seq body) (> body-len trigger) (seq oldest))
         (let [req    (build-summary-request oldest model)
               client  (or llm-client (:llm/client ctx))
               text   (if client
                        (call-summarizer-llm client req)
                        "[summary unavailable]")
               marker (build-summary-message text (now-ms))
               new-body (into [marker] protected)
               new-hist (if leading
                         (into [leading] new-body)
                         new-body)
               trimmed  (trim-history new-hist)]
           (assoc ctx :agent/state-delta
                  (merge delta {:agent/history trimmed})))
         ctx)))})

(def summarize-history-interceptor
  "Default-configured summarize-history interceptor for the base
   plugin. Carries `:name ::summarize-history` and `:slot
   :history-summarize`. With no LlmClient on the agent map, the
   interceptor falls back to `[summary unavailable]` placeholders
   (still emits the marker so the protected window is preserved)."
  (let [ix (summarize-history {:llm-client nil})]
    (assoc ix :name ::summarize-history :slot :history-summarize)))

(defn- body-window
  "Pick the trailing window of `body` (non-system messages) to keep.
   Guarantees that the most recent `:role user` message survives
   even when the raw `max-history-entries` window would have cut it
   off. Returns a vector."
  [body]
  (if (<= (count body) max-history-entries)
    (vec body)
    (let [n            (count body)
          window-start (- n max-history-entries)
          raw-tail     (subvec body window-start)
          last-user-ix (loop [i (dec n)]
                         (cond
                           (neg? i)                      nil
                           (= "user" (:role (nth body i))) i
                           :else                          (recur (dec i))))]
      (if (or (nil? last-user-ix)
              (>= last-user-ix window-start))
        (vec raw-tail)
        ;; Most recent user message sits BEFORE the raw window start;
        ;; anchor the window to include it (and everything after).
        ;; This can exceed `max-history-entries` when the user turn is
        ;; far back — acceptable because "never drop the most recent
        ;; user turn" outranks the hard cap.
        (vec (subvec body last-user-ix))))))

(defn trim-history
  "Trim `messages` to a conservative, OpenAI-compatible form:
   - always keep the leading `:role system` message if present;
   - keep the last `max-history-entries` non-system messages, but
     never drop the most recent `:role user` message;
   - truncate any `:role tool` `:content` over its cap with a
     `...[trimmed]` marker.

   `caps` (optional) is a map of tool-name -> char cap passed through to
   `truncate-tool-content` so tools with structurally large results
  (e.g. `clojure_eval` Clerk traces) can keep a higher cap than the
   default. Returns a vector of messages in original order."
  ([messages] (trim-history messages nil))
  ([messages caps]
   (let [msgs     (vec messages)
         first-   (first msgs)
         has-sys? (and (map? first-) (= "system" (:role first-)))
         body     (if has-sys? (subvec msgs 1) msgs)
         kept     (body-window body)
         result   (if has-sys? (into [first-] kept) kept)]
     (mapv #(truncate-tool-content % caps) result))))
(defn- tool-result->history-msg
  "Build a persisted `:role \"tool\"` message from a result map.
   Stamps `:name` from the call so per-tool `:tool-content-caps` apply
   when `trim-history` / `compose-context` later truncate content."
  [{:keys [call result]}]
  {:role         "tool"
   :tool_call_id (:id call)
   :name         (get-in call [:function :name])
   :content      (str result)})

(defn- flat-tool-history-msgs
  "Fallback when no `:agent/tool-transcript` was recorded: reconstruct a
   single assistant(tool_calls) + tool* block from a flat results vector.
   Prefer the transcript path for multi-turn ReAct exchanges."
  [tool-results]
  (into [{:role       "assistant"
          :content    ""
          :tool_calls (mapv :call tool-results)}]
        (mapv tool-result->history-msg tool-results)))

(defn build-exchange-history
  "Build the trimmed `:agent/history` for one exchange from the prior
   history + this exchange's user text, tool results, and final
   response. Shared by `store-exchange` (happy path) and `error-boundary`
   (partial-exchange recovery) so a mid-loop throw still persists the
   tool transcript that ran before the failure.

   Message order is OpenAI-compatible:
     1. optional user turn,
     2. tool cycles — prefer `tool-transcript` (per-turn assistant +
        tool messages accumulated by compose-tool-results) so multi-turn
        ReAct stays causally ordered; fall back to a single flat
        assistant(tool_calls)+tool* block reconstructed from
        `tool-results`,
     3. the final assistant text (the summary or direct response),
        when non-empty.

   `caps` (optional tool-name -> char cap) is threaded into
   `trim-history` so configured `:tool-content-caps` survive persistence,
   not only the in-flight request."
  ([prev-history user-text response tool-results]
   (build-exchange-history prev-history user-text response tool-results nil nil))
  ([prev-history user-text response tool-results caps]
   (build-exchange-history prev-history user-text response tool-results caps nil))
  ([prev-history user-text response tool-results caps tool-transcript]
   (let [with-user     (if (seq user-text)
                         (conj prev-history {:role "user" :content user-text})
                         prev-history)
         tool-msgs     (cond
                         (seq tool-transcript) (vec tool-transcript)
                         (seq tool-results)    (flat-tool-history-msgs tool-results)
                         :else                 [])
         with-tools    (into with-user tool-msgs)
         last          (peek with-tools)
         with-response (cond
                         (str/blank? response)
                         with-tools
                         (and last
                              (= "assistant" (:role last))
                              (empty? (:tool_calls last)))
                         (let [prev (str (:content last))]
                           (if (or (str/blank? prev)
                                   (= (str/trim prev) (str/trim (str response))))
                             (conj (pop with-tools)
                                   (assoc last :content response))
                             (conj (pop with-tools)
                                   (assoc last :content (str prev "\n\n" response)))))
                         :else
                         (conj with-tools {:role "assistant" :content response}))]
     (trim-history with-response caps))))

(def error-boundary
  "Handles any error raised by the chain. Clears the engine ::error
   key so the engine treats the error as handled and proceeds to
   the :leave phase; annotates ctx with `:error/raised` carrying
   the throwable + chain/stage. After this stage, the final ctx
   carries :error/raised and :leave stages still run.

   Partial-exchange recovery (audit 2026-06-24 rec #4): when a throw in
   :llm/:tools/:finalize prevents `store-exchange` (slot :history, last)
   from ever entering, its :leave never runs and the partial tool
   transcript would be lost. Because error-boundary is the FIRST
   interceptor (slot :guard), it is always on the stack when the error
   walk runs, so its :error handler snapshots whatever
   `:agent/all-tool-results` accumulated before the failure into
   `:agent/state-delta :agent/history`. The runtime still merges
   state-delta on a handled error, so the next exchange sees the partial
   transcript. When the throw happens AFTER store-exchange entered,
   store-exchange's own :leave overwrites this with the complete
   history — no double-persist."
  {:name ::error-boundary
   :error (fn [ctx ex]
            (let [state        (:agent/state ctx)
                  prev-history (or (:agent/history state) [])
                  user-text    (:exchange/user-text ctx)
                  response     (:exchange/response ctx)
                  tool-results (or (:agent/all-tool-results ctx) [])
                  transcript   (:agent/tool-transcript ctx)
                  caps         (get-in ctx [:agent/loop-opts :tool-content-caps])
                  partial      (when (or (seq tool-results) (seq transcript))
                                 (build-exchange-history prev-history user-text response
                                                         tool-results caps transcript))
                  delta        (when partial {:agent/history partial})]
              (cond->
               (-> ctx
                   (dissoc ::chain/error)
                   (assoc :error/raised
                          {:exception ex
                           :stage     (or (:chain/stage (ex-data ex))
                                          :unknown)}))
                delta (assoc :agent/state-delta
                             (merge (or (:agent/state-delta ctx) {}) delta)))))})

(defn- system-message*
  [state ctx]
  (let [base  (or (:agent/system-message state) "lateralus-v2 MVP")
        a     (:agent/system-append ctx)
        extra (cond (string? a) (str/trim a)
                    (sequential? a) (->> a (map str) (remove str/blank?) (str/join "\n\n"))
                    :else "")]
    (if (str/blank? extra) base (str base "\n\n" extra))))

(def compose-context
  "Build `:llm/request`; honors `:agent/system-append` from earlier slots."
  {:name ::compose-context
   :enter (fn [ctx]
            (let [state     (:agent/state ctx)
                  user-text (or (:exchange/user-text ctx) "")
                  recall    (or (:memory/recall ctx) [])
                  sys-msg   (system-message* state ctx)
                  history   (or (:agent/history state) [])
                  recalled  (mapv (fn [m]
                                    {:role    "system"
                                     :content (str "[recall] "
                                                   (if (map? m)
                                                     (:content m "")
                                                     m))})
                                  recall)
                  messages  (cond-> [{:role "system" :content sys-msg}]
                              (seq recalled) (into recalled)
                              (seq history)  (into history)
                              (seq user-text) (conj {:role "user" :content user-text}))
                  trimmed   (trim-history messages
                                            (get-in ctx [:agent/loop-opts :tool-content-caps]))]
              (assoc ctx
                     :llm/request
                     {:base-url (:base-url state)
                      :api-key  (:api-key state)
                      :model    (or (:model state) "stub/v0")
                      :messages trimmed}
                     :compose/trimmed? true
                     :agent/state-delta
                     (merge (or (:agent/state-delta ctx) {})
                            {:agent/last-request-messages trimmed}))))})

(def llm-call
  "Invoke the LlmClient. Wraps `call-llm`. No business logic — only
   reads `:llm/client` (pre-wired by the runtime) and `:llm/request`,
   writes `:llm/response`."
  {:name ::llm-call
   :enter call-llm})

(def parse-response
  "Extract :exchange/response, :tool/calls, and optional
   :exchange/thinking from :llm/response. Non-blank thinking is
   kept across tool-loop turns (a later blank reasoning field does
   not wipe a prior value)."
  {:name ::parse-response
   :enter (fn [ctx]
            (let [resp      (:llm/response ctx)
                  thinking  (response-thinking resp)]
              (cond-> (assoc ctx
                             :exchange/response (response-text resp)
                             :tool/calls        (response-tool-calls resp))
                (seq thinking) (assoc :exchange/thinking thinking))))})

(def store-exchange
  "Leave stage. Records the final exchange on ctx as
   `:memory/last-exchange` and appends the current user / assistant
   turn (including the assistant `tool_calls` turn and matching :role tool result messages) to `:agent/state-delta
   :agent/history` so the next exchange can include the full explicit
   transcript. The accumulated history is then routed through
   `trim-history` so big file reads do not blow up the context window
   forever.

   Tool persistence keys off `:agent/all-tool-results` (which survives
   across ReAct loop iterations), NOT `:tool/calls` (which only holds
   the final turn's calls). When the ensure-text-response summary path
   runs, the final turn has no tool calls but earlier turns did — so
   keying off `:tool/calls` would silently drop the tool results. We
   reconstruct the assistant tool-calling message from the results'
   `:call` fields so the OpenAI `:tool_calls` + :role tool pairing
   stays valid."
  {:name ::store-exchange
   :leave (fn [ctx]
            (let [state        (:agent/state ctx)
                  prev-history (or (:agent/history state) [])
                  user-text    (:exchange/user-text ctx)
                  response     (:exchange/response ctx)
                  tool-results (or (:agent/all-tool-results ctx) [])
                  transcript   (:agent/tool-transcript ctx)
                  caps         (get-in ctx [:agent/loop-opts :tool-content-caps])
                  history      (build-exchange-history prev-history user-text response
                                                       tool-results caps transcript)
                  delta        (merge (or (:agent/state-delta ctx) {})
                                      {:agent/history history})]
              (assoc ctx
                     :memory/last-exchange
                     {:session-id       (:exchange/session-id ctx)
                      :user-msg-id      (:exchange/user-msg-id ctx)
                      :assistant-msg-id (:exchange/assistant-msg-id ctx)
                      :response         (:exchange/response ctx)
                      :tool-calls       (or (:tool/calls ctx) [])
                      :tool-results     (or (:tool/results ctx) [])}
                     :agent/state-delta delta)))})

(def deliver-responses
  "Leave stage. Writes the final response to the agent's outgoing
   queue (`:exchange/delivered`). No I/O — caller polls the queue."
  {:name ::deliver-responses
   :leave (fn [ctx]
            (update ctx :exchange/delivered
                    (fnil conj [])
                    {:session-id (:exchange/session-id ctx)
                     :response   (:exchange/response ctx)}))})

(def notify
  "Leave stage. Final hook for listeners (UI, telemetry, etc.)."
  {:name  ::notify
   :leave (fn [ctx]
            (update ctx :exchange/notified
                    (fnil conj [])
                    {:session-id (:exchange/session-id ctx)
                     :event      :complete}))})

;; ---- All stages as data, for tests + assembly ----

(def all-stages
  "All defined stages. Order is not significant here; assembly
   happens through the base plugin in `kschltz.agent.plugins.base`."
  [error-boundary compose-context llm-call
   parse-response store-exchange deliver-responses notify])

;; ---- Schema self-check ----

(defn check-stages
  "Validate every defined stage against the Interceptor schema.
   Throws ex-info on the first failure. Returns :ok on success."
  []
  (doseq [stage all-stages
          :let [explain (m/explain schema/Interceptor (into {} stage))]]
    (when explain
      (throw (ex-info "Interceptor failed schema check"
                      {:interceptor (:name stage)
                       :explain (str explain)}))))
  :ok)
