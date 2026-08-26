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
  (:require [clojure.string :as str]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.llm.schemas :as schemas]
            [kschltz.agent.loop.act :as act]
            [kschltz.agent.loop.edits :as edits]
            [kschltz.agent.loop.stall :as stall]
            [kschltz.agent.loop.summary :as summary]
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
         compose-act-nudge-interceptor ensure-text-response-interceptor)

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
                  compact? (= :compact (get-in ctx [:agent/loop-opts :tool-schema-mode]))
                  define   (if compact? tool/compact-definition tool/tool-definition)
                  defs     (mapv define (vals registry))]
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
                   (edits/merge-edited results)
                   (update :agent/tool-transcript (fnil into []) new-msgs)
                   (update-in [:llm/request :messages]
                              (fn [msgs] (trim/trim-in-flight-messages (into msgs new-msgs) eff-caps))))))}))

(defn bump-loop-depth-interceptor
  "Interceptor that increments `:agent/tool-loop-depth`."
  []
  {:name ::bump-loop-depth
   :enter (fn [ctx]
            (update ctx :agent/tool-loop-depth (fnil inc 0)))})

(defn tool-loop-interceptor
  "`:finalize` interceptor that asks the `loop` strategy whether to
   continue and, if so, enqueues the follow-up chain. The registry is
   read from `:agent/tool-registry` on each turn so the same loop works
   even if the registry is injected late. Stall detection lives in
   `kschltz.agent.loop.stall`: (1) FAST — identical name+args set as last
   turn; (2) ARG-SHAPE — same primary arg (e.g. add-lib `:lib`) failing
   N>=2 times, even when sibling tools succeed. Counters persist on
   `:agent/state-delta` and are seeded from `:agent/state` so a rebuilt
   exchange ctx cannot drop them. Either stall →
   `ensure-text-response-interceptor` coaxes a summary."
  [loop]
  {:name ::tool-loop
   :slot :finalize
   :enter (fn [ctx]
            (let [ctx         (stall/seed-from-state ctx)
                  loop-opts   (:agent/loop-opts ctx)
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
                (stall/persist ctx {:agent/loop-continuing? false
                                    :agent/tool-cap-hit true})

                (not should-continue?)
                (assoc ctx :agent/loop-continuing? false)

                :else
                (let [{:keys [action patch]} (stall/decide ctx)]
                  (case action
                    :exact-stall
                    (stall/persist ctx (assoc patch
                                              :agent/loop-continuing? false
                                              :agent/stall-hit true))
                    :shape-stall
                    (stall/persist ctx (assoc patch
                                              :agent/loop-continuing? false
                                              :agent/shape-stall-hit true))
                    (-> (stall/persist ctx (assoc patch :agent/loop-continuing? true))
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

(defn compose-summary-request-interceptor
  "Strip tool-call scaffold (attempt 1) or replace history with a
   condensed digest (attempt 2+), then force a text-only request."
  []
  {:name ::compose-summary-request
   :enter summary/apply-summary-request})

(defn coerce-malformed-summary-interceptor
  "After parse-response on the summary mini-chain: empty-content
   tool_calls despite stripped tools are treated as a blank answer."
  []
  {:name ::coerce-malformed-summary
   :enter summary/coerce-malformed-summary})

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

(defn compose-act-nudge-interceptor
  "Keep tools on the request and append the planning reply + a system
   nudge so the follow-up turn can implement instead of yielding."
  []
  {:name ::compose-act-nudge
   :enter act/apply-nudge})

(defn ensure-text-response-interceptor
  "`:finalize` interceptor placed AFTER `tool-loop-interceptor`. When the
   loop is NOT continuing and :exchange/response is blank, enqueue a
   final mini-chain to coax a textual answer: a summary request when tool
   results exist, or an empty-response retry otherwise. Attempts are capped
   via :agent/summary-attempts (max max-summary-attempts) and
   :agent/empty-retry-attempts (max max-empty-retry-attempts). When the
   cap is exhausted, sets :agent/summary-failed? / :agent/empty-retry-failed?
   and returns ctx unchanged so the CLI fallback surfaces a clear message.
   A planning-only reply (\"I'll implement X\") with no tool_calls is
   NOT treated as a final answer: the interceptor nudges and re-enters
   the ReAct follow-up chain so the model can call tools in this
   exchange. Cap: `act/max-act-nudge-attempts`. When a genuine final
   answer is present or the loop is still continuing, this interceptor
   is a no-op. `loop` is used to enqueue the same follow-up chain as
   `tool-loop-interceptor`."
  [loop]
  {:name ::ensure-text-response
   :slot :finalize
   :enter (fn [ctx]
            (let [summary-attempts (get ctx :agent/summary-attempts 0)
                  retry-attempts   (get ctx :agent/empty-retry-attempts 0)
                  act-attempts     (get ctx :agent/act-nudge-attempts 0)
                  response         (:exchange/response ctx)
                  continuing?      (:agent/loop-continuing? ctx)
                  tools-ran?       (seq (:agent/all-tool-results ctx))
                  registry         (or (:agent/tool-registry ctx) {})
                  ;; `:tool/calls` holds the CURRENT turn's calls (set by
                  ;; parse-response, trimmed by dispatch-tools). When the
                  ;; loop stopped (continuing? false) AND the last turn
                  ;; still emitted tool_calls, the model was mid-tool —
                  ;; possibly with non-blank preamble text. That preamble
                  ;; is NOT a final answer; force a summary so the model
                  ;; digests the tool results into real text. A non-blank
                  ;; response with NO tool_calls is a clean final answer
                  ;; unless it is a planning-only announcement.
                  last-turn-called-tools? (seq (:tool/calls ctx))
                  planning? (act/planning-only?
                             response
                             {:tool-calls (:tool/calls ctx)
                              :registry   registry
                              :loop-opts  (:agent/loop-opts ctx)})]
              (cond
                continuing? ctx
                (and planning? (< act-attempts act/max-act-nudge-attempts))
                (-> ctx
                    (update :agent/act-nudge-attempts (fnil inc 0))
                    (assoc :agent/loop-continuing? true)
                    (chain/enqueue (into [(compose-act-nudge-interceptor)]
                                         (-follow-up-chain loop registry))))
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
                                    (coerce-malformed-summary-interceptor)
                                    (ensure-text-response-interceptor loop)]))
                (and tools-ran? (>= summary-attempts max-summary-attempts))
                (summary/apply-fallback ctx)
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
