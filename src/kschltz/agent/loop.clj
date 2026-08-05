(ns kschltz.agent.loop
  "ReAct-style tool-calling loop for lateralus.

   The `Loop` protocol abstracts whether to run another LLM turn and how
   to build that turn's interceptor chain. Default `ReActLoop` continues
   while at least one dispatched tool was implemented and a depth cap
   has not been reached.

   Slotting: `:compose` injects tools; `:tools` dispatches and composes
   results; `:finalize` loops or ensures a textual response. Provider-
   neutral tool data lives on ctx; OpenAI-shaped messages are built only
   when composing the follow-up request."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.llm.schemas :as schemas]
            [kschltz.agent.loop.trim :as trim]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.transitions.interceptors :as tr.ix]
            [malli.core :as m]
            [malli.error :as me]))

(def ^:private max-loop-depth
  "Safety cap on follow-up LLM calls inside a single exchange."
  5)

(def ^:private max-self-heal-attempts
  "Cap on Malli self-heal retries for invalid outgoing requests."
  3)

;; ---- Loop protocol ----

(defprotocol Loop
  "Strategy for multi-turn tool-calling loops."
  (-continue? [this ctx]
    "Return true if the chain should run another LLM turn from `ctx`.")
  (-follow-up-chain [this registry]
    "Return the interceptor vector for one follow-up turn. `registry`
     is a map of tool name -> Tool implementation."))

;; Forward declarations for interceptors used inside ReActLoop.
(declare bump-loop-depth-interceptor compose-tool-results-interceptor
         llm-call-with-self-heal dispatch-tools-interceptor tool-loop-interceptor
         compose-summary-request-interceptor compose-empty-retry-interceptor
         ensure-text-response-interceptor)

;; ---- ReAct loop implementation ----

(defn- implemented-result?
  "True when a tool result is not the unavailable-tool marker. Detects the
   exact `is not available in this session` phrase (the marker emitted by
   `tool/execute-tools` for unregistered tools) rather than the looser
   `Tool '` prefix, so a validation error that happens to start with
   `Tool '<name> ...` (audit 2026-07 rec #7) is NOT mistaken for an
   unavailable-tool marker — a validation-failed result IS an implemented
   result (the tool ran and reported a schema failure, it did not go missing)."
  [result-map]
  (not (str/includes? (str (:result result-map)) "is not available in this session")))

(defrecord ReActLoop [max-depth]
  Loop
  (-continue? [_ ctx]
    (let [depth   (get ctx :agent/tool-loop-depth 0)
          results (or (:tool/results ctx) [])]
      (and (< depth max-depth)
           (seq results)
           (some implemented-result? results))))

  (-follow-up-chain [this _registry]
    ;; Order mirrors the base chain's :llm -> :tools(dispatch,
    ;; harvest-transitions, apply-transitions, compose) -> :finalize
    ;; slots. compose-tool-results MUST run AFTER dispatch-tools so it
    ;; appends THIS turn's freshly-produced :tool/results (not the
    ;; previous turn's stale ones). The previous turn's results are
    ;; already in :llm/request :messages from that turn's compose, so
    ;; the model sees them at the llm-call below.
    ;; (Placing compose BEFORE dispatch — the old order — re-appended
    ;; the previous turn's results every follow-up turn, duplicating
    ;; the [assistant(tool_calls), tool*] block and growing messages
    ;; ~2x per turn — the "hands off before complete" root cause.)
    ;; Transition harvest/apply must mirror the base :tools slot so a
    ;; set_llm_config / mcp_* call mid-ReAct patches :llm/request (and
    ;; reconciles MCP) before the next follow-up llm-call. Apply runs
    ;; before compose so MCP reconcile outcomes land in tool messages.
    [(bump-loop-depth-interceptor)
     (llm-call-with-self-heal)
     ix/llm-call
     ix/parse-response
     (dispatch-tools-interceptor)
     (tr.ix/harvest-transitions-interceptor)
     (tr.ix/apply-transitions-interceptor)
     (compose-tool-results-interceptor)
     (tool-loop-interceptor this)
     (ensure-text-response-interceptor this)]))

(defn react-loop
  "Construct a `ReActLoop` with an optional `max-depth` (default 5)."
  ([] (react-loop max-loop-depth))
  ([max-depth] (->ReActLoop max-depth)))

(defn configured-react-loop
  "Build a `ReActLoop` from an opts map, allowing the Integrant config
   to set `:max-loop-depth`. Falls back to the default cap when unset."
  ([opts]
   (react-loop (or (:max-loop-depth opts) max-loop-depth))))

;; ---- Message builders ----

(defn- assistant-tool-message
  "Build the assistant message that requested the tool calls. Must
   immediately precede the matching tool-result messages in the chat
   history."
  [ctx]
  (let [calls (or (:tool/calls ctx) [])]
    (when (seq calls)
      {:role "assistant"
       :content (or (:exchange/response ctx) "")
       :tool_calls calls})))

(defn- tool-result-message
  "Build an OpenAI-shaped tool-result message from a provider-neutral
   result map. Stamps the tool's `:name` onto the message so the
   truncation site can apply a per-tool char cap (audit 2026-07 rec #5):
  `clojure_eval` / `clojure_add_lib` results are structurally large
   (Clerk render traces) and must survive the default 2000-char cap
   intact, while ordinary tool results stay bounded."
  [{:keys [call result]}]
  {:role "tool"
   :tool_call_id (:id call)
   :name (get-in call [:function :name])
   :content (str result)})

;; ---- Interceptors ----

(defn inject-tools-interceptor
  "`:compose` interceptor that adds tool definitions to the outgoing
   `:llm/request`. Reads the registry from `:agent/tool-registry` on the
   context. When no registry is present, injects an empty `:tools`
   vector."
  []
  {:name ::inject-tools
   :slot :compose
   :enter (fn [ctx]
            (let [req      (:llm/request ctx)
                  registry (or (:agent/tool-registry ctx) {})
                  defs     (mapv tool/tool-definition (vals registry))]
              (assoc ctx :llm/request (assoc req :tools defs))))})

(defn- tool-call-limit
  "Compute how many of this turn's tool_calls may execute given the
   per-turn and per-exchange caps. The exchange budget is remaining
   headroom (`max-tool-calls-per-exchange` minus results already
   accumulated), so a first-turn burst cannot blow past the exchange
   cap when per-turn is set higher (e.g. turn=100, exchange=20)."
  [loop-opts already-count]
  (let [max-per-turn (:max-tool-calls-per-turn loop-opts)
        total-cap    (:max-tool-calls-per-exchange loop-opts)
        room         (when total-cap (max 0 (- total-cap already-count)))]
    (cond
      (and room max-per-turn) (min max-per-turn room)
      room                    room
      max-per-turn            max-per-turn
      :else                   nil)))

(defn dispatch-tools-interceptor
  "`:tools` interceptor: execute tool calls against the registry in
   `:agent/tool-registry`, store `:tool/results`, and accumulate into
   `:agent/all-tool-results`. When a requested tool is unregistered,
   append a system message listing available tools so the follow-up turn
   self-corrects. Caps: `:max-tool-calls-per-turn` and the remaining
   `:max-tool-calls-per-exchange` budget (already-executed results count
   against the exchange total BEFORE this turn runs). When the model
   emits more tool_calls than the effective limit, only the first N
   execute and `:tool/calls` is trimmed to match (OpenAI requires every
   tool_call to have a matching tool result). The dropped count is
   recorded as `:agent/tool-calls-dropped` for the CLI/logs."
  []
  {:name ::dispatch-tools
   :slot :tools
   :enter (fn [ctx]
            (let [loop-opts      (:agent/loop-opts ctx)
                  all-calls      (or (:tool/calls ctx) [])
                  already        (count (or (:agent/all-tool-results ctx) []))
                  limit          (tool-call-limit loop-opts already)
                  registry       (or (:agent/tool-registry ctx) {})
                  capped-calls   (if (and limit (> (count all-calls) limit))
                                   (take limit all-calls)
                                   all-calls)
                  dropped-count  (- (count all-calls) (count capped-calls))
                  results        (tool/execute-tools registry ctx capped-calls)
                  ctx'           (-> ctx
                                     (assoc :tool/calls capped-calls
                                            :tool/results results)
                                     (update :agent/all-tool-results (fnil into []) results))
                  any-unavailable? (some (fn [r]
                                           (str/includes? (str (:result r))
                                                          "is not available in this session"))
                                         results)]
              (cond-> (if any-unavailable?
                        (update-in ctx' [:llm/request :messages]
                                   conj {:role "system"
                                         :content (str "One or more requested tools are not available. "
                                                       "Available tools: "
                                                       (str/join ", " (sort (keys registry))))})
                        ctx')
                (pos? dropped-count)
                (-> (assoc :agent/tool-calls-dropped dropped-count)
                    (update-in [:llm/request :messages]
                               conj {:role "system"
                                     :content (str "Truncated " dropped-count
                                                   " tool call(s) this turn; only the first "
                                                   (count capped-calls)
                                                   " will execute. Continue with the executed results or stop.")})))))})

(defn compose-tool-results-interceptor
  "`:tools` interceptor that appends the assistant tool-calling message
   and matching tool-result messages to `:llm/request :messages` for the
   follow-up turn, then bounds the in-flight messages vector in both
   count and size via `trim-in-flight-messages` (audit 2026-06-24 rec #6:
   a high max-loop-depth or large per-turn tool-call burst could grow
   the in-flight request unbounded).

   Also appends the same assistant+tool messages onto
   `:agent/tool-transcript` so `store-exchange` can persist multi-turn
   ReAct cycles as separate assistant/tool blocks instead of collapsing
   every call into one synthetic assistant turn."
  ([] (compose-tool-results-interceptor nil))
  ([caps]
   {:name ::compose-tool-results
    :slot :tools
    :enter (fn [ctx]
             (let [results (or (:tool/results ctx) [])
                   assistant-msg (assistant-tool-message ctx)
                   result-msgs (mapv tool-result-message results)
                   new-msgs (vec (if assistant-msg
                                   (cons assistant-msg result-msgs)
                                   result-msgs))
                   eff-caps (or caps (get-in ctx [:agent/loop-opts :tool-content-caps]))]
               (-> ctx
                   (update :agent/tool-transcript (fnil into []) new-msgs)
                   (update-in [:llm/request :messages]
                              (fn [msgs] (trim/trim-in-flight-messages (into msgs new-msgs) eff-caps))))))}))

(defn bump-loop-depth-interceptor
  "Interceptor that increments `:agent/tool-loop-depth`."
  []
  {:name ::bump-loop-depth
   :enter (fn [ctx]
            (update ctx :agent/tool-loop-depth (fnil inc 0)))})

(defn- tool-call-sig
  "A stable signature for the current set of tool calls, used for
   stall detection. Compares tool name + raw arguments JSON (the FAST
   path — catches the model emitting the IDENTICAL tool call twice)."
  [calls]
  (mapv (fn [c] {(get-in c [:function :name])
                 (get-in c [:function :arguments])})
        calls))

(def ^:private tool-primary-arg-keys
  "Map of tool-name -> arg keys identifying the *target* of a call (the
   'primary arg'). Repeated calls with the SAME primary arg but differing
  secondary args (e.g. `clojure_add_lib` same `:lib`, variant `:require`)
   are treated as the same SHAPE for arg-shape stall detection
   (verify-round-3 FIX 3). Tools not listed fall back to the full args
   string (no behavior change)."
 {"clojure_add_lib" [:lib :coords]})

(defn- tool-call-shape
  "Coarser signature for arg-shape stall detection: tool name + the
  primary-arg subset of the arguments JSON. Returns `[name primary-arg-str]`.
  Tools without a known primary-arg key set use the whole args string (shape
  = exact signature, no new behavior). Parses the OpenAI args JSON with
  cheshire; on parse failure falls back to the raw string."
  [call]
  (let [name      (get-in call [:function :name])
        args-str   (get-in call [:function :arguments])
        pkeys      (get tool-primary-arg-keys name)]
    (if pkeys
      (let [parsed (try (json/parse-string args-str true)
                         (catch Throwable _ nil))]
        [name (if (map? parsed)
                (pr-str (select-keys parsed pkeys))
                args-str)])
      [name args-str])))

(defn- error-status?
  "True when a parsed JSON `:status` is an error/timeout shape.
   Cheshire serializes Clojure keywords as JSON strings and parses them
   back as strings, so real tool envelopes carry `\"error\"` / `\"timeout\"`
   rather than `:error` / `:timeout`. Accept both so stall detection
   works on live envelopes (not only on in-process keyword maps)."
  [status]
  (contains? #{:error :timeout "error" "timeout"} status))

(defn- result-error-shape?
  "True when a tool result envelope indicates a FAILURE shape worth counting
   toward arg-shape stall detection: a JSON envelope with status
   error/timeout or `:loaded? false` (the verify-round-3 add-lib re-spam
   produced `loaded? false` every turn), OR the unavailable-tool marker.
   A non-JSON string that is NOT the marker (a plain success value) does
   NOT count."
  [result-map]
  (let [r (:result result-map)]
    (if (string? r)
      (if-let [parsed (try (json/parse-string r true) (catch Throwable _ nil))]
        (or (error-status? (:status parsed))
            (false? (:loaded? parsed)))
        (str/includes? (str r) "is not available in this session"))
      false)))

(defn tool-loop-interceptor
  "`:finalize` interceptor that asks the `loop` strategy whether to
   continue and, if so, enqueues the follow-up chain. The registry is
   read from `:agent/tool-registry` on each turn so the same loop works
   even if the registry is injected late. Stall detection has two layers:
   (1) FAST — the model emits the IDENTICAL tool-call set (name + raw args)
   as last turn → do not enqueue; (2) ARG-SHAPE (verify-round-3 FIX 3) —
  the SAME tool + same PRIMARY arg (e.g. `:lib` for `clojure_add_lib`)
   with differing secondary args for N>=2 all-error turns → trip
   `:agent/shape-stall-hit` (the round-2 re-spam of add-lib with variant
   `:require` bypassed the exact guard). Either stall →
   `ensure-text-response-interceptor` coaxes a summary. Sets
   `:agent/loop-continuing?` for the adjacent interceptor."
  [loop]
  {:name ::tool-loop
   :slot :finalize
   :enter (fn [ctx]
            (let [loop-opts   (:agent/loop-opts ctx)
                  ctx-max-depth (:max-loop-depth loop-opts)
                  total-cap    (:max-tool-calls-per-exchange loop-opts)
                  depth        (get ctx :agent/tool-loop-depth 0)
                  results      (or (:tool/results ctx) [])
                  all-results  (:agent/all-tool-results ctx)
                  implemented? (some implemented-result? results)
                  over-total?  (and total-cap (>= (count all-results) total-cap))
                  ;; When ctx-max-depth is set it OVERRIDES the record's
                  ;; max-depth (so config can raise the cap above the
                  ;; default 5). Otherwise fall back to -continue? which
                  ;; uses the record's max-depth + results/implemented checks.
                  should-continue? (cond
                                     over-total? false
                                     ctx-max-depth (and (< depth ctx-max-depth)
                                                        (seq results)
                                                        implemented?)
                                     :else (-continue? loop ctx))]
              (cond
                over-total?
                (assoc ctx :agent/loop-continuing? false :agent/tool-cap-hit true)

                (not should-continue?)
                (assoc ctx :agent/loop-continuing? false)

                :else
                (let [calls      (or (:tool/calls ctx) [])
                      sig        (tool-call-sig calls)
                      last-sig   (:agent/last-tool-call-sig ctx)
                      ;; verify-round-3 FIX 3: arg-shape stall guard.
                      ;; exact-sig fast path catches IDENTICAL calls; this
                      ;; catches same tool + same primary arg with differing
                      ;; secondary args (add-lib same :lib, variant :require)
                      ;; when every result is an error shape. Same shape for
                      ;; N>=2 all-error turns → trip :agent/shape-stall-hit.
                      ;; A turn with any non-error result resets the counter.
                      shape        (set (mapv tool-call-shape calls))
                      last-shape   (:agent/last-tool-shape ctx)
                      prev-count   (get ctx :agent/shape-err-count 0)
                      turn-error?  (and (seq results)
                                        (every? result-error-shape? results))
                      same-shape?  (and (some? last-shape) (= shape last-shape))
                      new-count    (cond (not turn-error?) 0
                                         same-shape?      (inc prev-count)
                                         :else            1)
                      shape-stall? (and turn-error? same-shape?
                                         (>= new-count 2))]
                  (cond
                    (= sig last-sig)
                    (assoc ctx :agent/loop-continuing? false :agent/stall-hit true)
                    shape-stall?
                    (assoc ctx :agent/loop-continuing? false :agent/shape-stall-hit true)
                    :else
                    (-> ctx
                        (assoc :agent/last-tool-call-sig sig
                               :agent/last-tool-shape  shape
                               :agent/shape-err-count  new-count
                               :agent/loop-continuing?  true)
                        (chain/enqueue (-follow-up-chain loop
                                                         (or (:agent/tool-registry ctx) {})))))))))})

;; ---- Self-heal ----

(defn- humanize-request-errors
  "Return humanized Malli validation errors for `req`, or nil when valid."
  [req]
  (some-> schemas/ChatRequest (m/explain req) (me/humanize)))

(defn- repair-request-with-error
  "Append a system message describing the validation error so the model
   can self-correct."
  [ctx explain]
  (update-in ctx [:llm/request :messages]
             conj {:role "system"
                   :content (str "The request built from your last tool response failed schema validation. Fix the tool call format and try again. Errors: "
                                 (pr-str explain))}))

(defn llm-call-with-self-heal
  "Interceptor placed immediately before `ix/llm-call`. Validates the
   outgoing request; if invalid it appends a system message with the
   humanized Malli error, terminates the rest of the current queue, and
   re-enqueues itself followed by the PREVIOUSLY remaining queue
   (typically `llm-call` → `parse-response` → tools → finalize).

   Preserving the remaining queue is required: a heal that only
   re-enqueued `[self-heal llm-call parse-response]` dropped
   `dispatch-tools` / `tool-loop` / `ensure-text-response`, so a healed
   response carrying `tool_calls` never executed tools. Self-heal
   attempts are capped by `:agent/self-heal-attempts`."
  []
  {:name ::llm-call-with-self-heal
   :enter (fn [ctx]
            (let [attempts (get ctx :agent/self-heal-attempts 0)]
              (if (>= attempts max-self-heal-attempts)
                ctx
                (if-some [explain (humanize-request-errors (:llm/request ctx))]
                  ;; Engine has already popped this interceptor off the
                  ;; queue before :enter runs, so ::queue is everything
                  ;; that still needs to run after a successful heal
                  ;; (llm-call, parse-response, tools, finalize, ...).
                  (let [remaining (vec (or (::chain/queue ctx) []))]
                    (-> ctx
                        (repair-request-with-error explain)
                        (update :agent/self-heal-attempts (fnil inc 0))
                        chain/terminate
                        (chain/enqueue (into [(llm-call-with-self-heal)]
                                             remaining))))
                  ctx))))})

;; ---- Ensure a textual response when the loop stops ----

(def ^:private max-summary-attempts
  "Cap on summary LLM calls per exchange when the loop stops with a blank
   response. A model can return empty even with :tools stripped (refusal,
   truncation); a second attempt with the same text-only request is cheap
   insurance. After the cap, :agent/summary-failed? is set so the CLI can
   surface a clear message."
  2)

(def ^:private max-empty-retry-attempts
  "Cap on empty-response retry calls per exchange when the model produced
   neither text nor tool calls on the first turn."
  2)

(defn- strip-tool-call-scaffold
  "For the summary turn, remove the structural tool-call scaffolding from
  `messages` so a tool-happy model cannot echo prior assistant
  `:tool_calls` (verify-round-3 FIX 2 — the real criterion-3 blocker:
  glm-5.2 / kimi-k2.6 emitted empty-content `tool_calls` on the summary
  turn because the history still carried prior assistant `:tool_calls` to
  echo, even with `:tool_choice` none and `:tools` stripped). Assistant
  messages keep their prose `:content` (dropped entirely when the only
  payload was `:tool_calls` with blank prose) and lose their `:tool_calls`;
  `:role` `\"tool\"` result messages are rewritten to `:role` `\"system\"`
  with a 'Tool <name> returned: <content>' prefix so the model still sees
  the results as context but there is no `tool_call`/`tool` pairing to echo
  (and no orphan-`tool` OpenAI 400). Other messages pass through unchanged."
  [messages]
  (into []
        (keep (fn [m]
                (cond
                  (and (= "assistant" (:role m)) (seq (:tool_calls m)))
                  (let [c (:content m)]
                    (when (and (string? c) (not (str/blank? c)))
                      (dissoc m :tool_calls)))

                  (= "tool" (:role m))
                  {:role "system"
                   :content (str "Tool " (or (:name m) "?") " returned: " (:content m))}

                  :else m)))
        messages))

(defn compose-summary-request-interceptor
  "Append a system message telling the model to produce the final answer
   from the tool results, STRIP :tools, set :tool-choice \"none\", AND
   strip the prior tool-call scaffolding from history
   (`strip-tool-call-scaffold`) so a tool-happy model cannot echo prior
   assistant :tool_calls (verify-round-3 FIX 2 — the real criterion-3
   blocker; :tool_choice none + stripped :tools alone was insufficient for
   glm-5.2 / kimi-k2.6 which echoed the history's :tool_calls). Used by
   ensure-text-response-interceptor when the loop stopped blank but tool
   results exist."
  []
  {:name ::compose-summary-request
   :enter (fn [ctx]
            (-> ctx
                (update-in [:llm/request :messages] strip-tool-call-scaffold)
                (update-in [:llm/request :messages]
                           conj {:role "system"
                                 :content "You have finished calling tools. Using the tool results above, produce the final answer for the user."})
                (update :llm/request
                        #(-> % (dissoc :tools) (assoc :tool-choice "none")))))})

(defn compose-empty-retry-interceptor
  "Append a system message nudging the model to reply after it returned
   an empty response with no tool calls, STRIP :tools, AND set :tool-choice
   \"none\" so the model cannot escape into a tool call. Used by
   ensure-text-response-interceptor on the first turn when the model
   produced nothing at all."
  []
  {:name ::compose-empty-retry
   :enter (fn [ctx]
            (-> ctx
                (update-in [:llm/request :messages]
                           conj {:role "system"
                                 :content "Your last response was empty. Reply to the user."})
                (update :llm/request
                        #(-> % (dissoc :tools) (assoc :tool-choice "none")))))})

(defn ensure-text-response-interceptor
  "`:finalize` interceptor placed AFTER `tool-loop-interceptor`. When the
   loop is NOT continuing and :exchange/response is blank, enqueue a
   final mini-chain to coax a textual answer: a summary request when tool
   results exist, or an empty-response retry otherwise. Attempts are capped
   via :agent/summary-attempts (max max-summary-attempts) and
   :agent/empty-retry-attempts (max max-empty-retry-attempts). When the
   cap is exhausted, sets :agent/summary-failed? / :agent/empty-retry-failed?
   and returns ctx unchanged so the CLI fallback surfaces a clear message.
   When a response is already present or the loop is still continuing, this
   interceptor is a no-op. `loop` is accepted for symmetry with
   tool-loop-interceptor and ignored."
  [loop]
  {:name ::ensure-text-response
   :slot :finalize
   :enter (fn [ctx]
            (let [summary-attempts (get ctx :agent/summary-attempts 0)
                  retry-attempts   (get ctx :agent/empty-retry-attempts 0)
                  response         (:exchange/response ctx)
                  continuing?      (:agent/loop-continuing? ctx)
                  tools-ran?       (seq (:agent/all-tool-results ctx))
                  ;; `:tool/calls` holds the CURRENT turn's calls (set by
                  ;; parse-response, trimmed by dispatch-tools). When the
                  ;; loop stopped (continuing? false) AND the last turn
                  ;; still emitted tool_calls, the model was mid-tool —
                  ;; possibly with non-blank preamble text. That preamble
                  ;; is NOT a final answer; force a summary so the model
                  ;; digests the tool results into real text. A non-blank
                  ;; response with NO tool_calls is a clean final answer —
                  ;; deliver it as-is.
                  last-turn-called-tools? (seq (:tool/calls ctx))]
              (cond
                continuing? ctx
                (and (not (str/blank? response)) (not last-turn-called-tools?)) ctx
                ;; summary path: tools ran this exchange AND the loop
                ;; stopped (either blank response, or non-blank preamble
                ;; alongside tool_calls — the cap/stall/depth case).
                (and tools-ran? (< summary-attempts max-summary-attempts))
                (-> ctx
                    (update :agent/summary-attempts (fnil inc 0))
                    (chain/enqueue [(compose-summary-request-interceptor)
                                    (llm-call-with-self-heal)
                                    ix/llm-call
                                    ix/parse-response
                                    (ensure-text-response-interceptor loop)]))
                (and tools-ran? (>= summary-attempts max-summary-attempts))
                (assoc ctx :agent/summary-failed? true)
                ;; empty-retry path: no tools ran, response blank
                (< retry-attempts max-empty-retry-attempts)
                (-> ctx
                    (update :agent/empty-retry-attempts (fnil inc 0))
                    (chain/enqueue [(compose-empty-retry-interceptor)
                                    (llm-call-with-self-heal)
                                    ix/llm-call
                                    ix/parse-response
                                    (ensure-text-response-interceptor loop)]))
                :else
                (assoc ctx :agent/empty-retry-failed? true))))})
