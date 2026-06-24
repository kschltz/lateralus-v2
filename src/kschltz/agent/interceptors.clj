/ (ns kschltz.agent.interceptors
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
    (:require [kschltz.agent.chain :as chain]
              [kschltz.agent.interceptors.schema :as schema]
              [kschltz.agent.llm.client :as llm-client]
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
    (assoc ctx :llm/response (llm-client/-call client req))))

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

;; TODO memory-followup: delete this stub. It is a no-op marker
;; so a future history-trimming PR has a clear `find-fn + replace`
;; target (token budget, recall window, etc.).
(def max-history-entries
  "Hard cap on the number of non-system messages retained in
   `:agent/history` after trimming. Keeps context growth bounded so
   large file reads do not blow up the request forever."
  40)

(def ^:private max-tool-content-chars
  "Hard cap on the `:content` of any `:role tool` message retained
   in history. Tool results larger than this are truncated with a
   `...[trimmed]` marker so the model still knows the original was
   larger but does not pay the token cost on every subsequent turn."
  2000)

(defn truncate-tool-content
  "Truncate a single message's `:content` if it is a `:role tool`
   message whose content exceeds `max-tool-content-chars`. Non-tool
   messages (and tool messages already under the cap) pass through
   unchanged. The truncation appends a `...[trimmed]` marker so the
   model can see it was elided, not silently corrupted."
  [msg]
  (let [content (:content msg)
        over?   (and (= "tool" (:role msg))
                     (string? content)
                     (> (count content) max-tool-content-chars))]
    (if over?
      (assoc msg :content
             (str (subs content 0 max-tool-content-chars)
                  "...[trimmed]"))
      msg)))

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
   - truncate any `:role tool` `:content` over
     `max-tool-content-chars` with a `...[trimmed]` marker.

   Returns a vector of messages in original order."
  [messages]
  (let [msgs     (vec messages)
        first-   (first msgs)
        has-sys? (and (map? first-) (= "system" (:role first-)))
        body     (if has-sys? (subvec msgs 1) msgs)
        kept     (body-window body)
        result   (if has-sys? (into [first-] kept) kept)]
    (mapv truncate-tool-content result)))
(defn build-exchange-history
  "Build the trimmed `:agent/history` for one exchange from the prior
   history + this exchange's user text, tool results, and final
   response. Shared by `store-exchange` (happy path) and `error-boundary`
   (partial-exchange recovery) so a mid-loop throw still persists the
   tool transcript that ran before the failure.

   Message order is OpenAI-compatible:
     1. optional user turn,
     2. when tools ran: assistant tool-calling turn (content empty —
        the model emitted calls, not text) carrying :tool_calls
        reconstructed from the results' :call fields,
     3. one {:role tool :tool_call_id ...} entry per result,
     4. the final assistant text (the summary or direct response),
        when non-empty."
  [prev-history user-text response tool-results]
  (let [had-tools?        (seq tool-results)
        with-user         (if (seq user-text)
                            (conj prev-history {:role "user" :content user-text})
                            prev-history)
        with-tool-asm     (if had-tools?
                            (conj with-user
                                  {:role       "assistant"
                                   :content    ""
                                   :tool_calls (mapv :call tool-results)})
                            with-user)
        with-tool-results (if had-tools?
                            (into with-tool-asm
                                  (mapv (fn [{:keys [call result]}]
                                          {:role         "tool"
                                           :tool_call_id (:id call)
                                           :content      (str result)})
                                        tool-results))
                            with-tool-asm)
        with-response     (if (seq response)
                            (conj with-tool-results
                                  {:role "assistant" :content response})
                            with-tool-results)]
    (trim-history with-response)))

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
                  partial      (when (seq tool-results)
                                 (build-exchange-history prev-history user-text response tool-results))
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

(def compose-context
  "Build `:llm/request` from :agent/state + :exchange/user-text +
   recall + explicit conversation history. Records the assembled
   message vector under `:agent/last-request-messages` in
   `:agent/state-delta` so the self/status tool can report the
   context size of the last completed exchange."
  {:name ::compose-context
   :enter (fn [ctx]
            (let [state     (:agent/state ctx)
                  user-text (or (:exchange/user-text ctx) "")
                  recall    (or (:memory/recall ctx) [])
                  sys-msg   (or (:agent/system-message state) "lateralus-v2 MVP")
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
                  trimmed   (trim-history messages)]
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
  "Extract :exchange/response and :tool/calls from :llm/response."
  {:name ::parse-response
   :enter (fn [ctx]
            (let [resp (:llm/response ctx)]
              (assoc ctx
                     :exchange/response (response-text resp)
                     :tool/calls        (response-tool-calls resp))))})

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
                  history      (build-exchange-history prev-history user-text response tool-results)
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
