(ns kschltz.agent.loop
  "ReAct-style tool-calling loop for lateralus.

   The `Loop` protocol abstracts the decision of whether to run another
   LLM turn and how to build the interceptor chain for that turn. The
   default `ReActLoop` continues while at least one dispatched tool was
   implemented and a depth cap has not been reached.

   Interceptors in this namespace are slot-tagged so the base plugin can
   assemble them in the right place. They read the tool registry from the
   context key `:agent/tool-registry` (a map of name -> Tool), which is
   expected to be seeded before the `:compose` stage runs.

   Slotting:
     :compose  — inject tool definitions into the LLM request
     :tools    — execute dispatched tools and compose tool-result messages
     :finalize — decide whether to loop back to the LLM; ensure a
                textual response when the loop stops

   The loop keeps provider-neutral tool data on the context
   (`:tool/calls`, `:tool/results`, `:agent/all-tool-results`) and
   converts to OpenAI-shaped messages only when composing the follow-up
   request. Anthropic / other adapters can be layered in later."
  (:require [clojure.string :as str]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.llm.schemas :as schemas]
            [kschltz.agent.tool :as tool]
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
  "True when a tool result is not the unavailable-tool marker."
  [result-map]
  (not (str/starts-with? (str (:result result-map)) "Tool '")))

(defrecord ReActLoop [max-depth]
  Loop
  (-continue? [_ ctx]
    (let [depth   (get ctx :agent/tool-loop-depth 0)
          results (or (:tool/results ctx) [])]
      (and (< depth max-depth)
           (seq results)
           (some implemented-result? results))))

  (-follow-up-chain [this _registry]
    [(bump-loop-depth-interceptor)
     (compose-tool-results-interceptor)
     (llm-call-with-self-heal)
     ix/llm-call
     ix/parse-response
     (dispatch-tools-interceptor)
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
   result map."
  [{:keys [call result]}]
  {:role "tool"
   :tool_call_id (:id call)
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

(defn dispatch-tools-interceptor
  "`:tools` interceptor that executes tool calls against the registry
   in `:agent/tool-registry` and stores `:tool/results`. Also accumulates
   every result in `:agent/all-tool-results` so the final ctx records tools
   that ran in earlier loop iterations. When any requested tool is not in
   the registry, appends a system message listing the available tools so
   the follow-up turn can self-correct instead of silently stopping.

   Per-turn cap: reads `:max-tool-calls-per-turn` from `:agent/loop-opts`.
   When the model emits more tool_calls than the cap, only the first N are
   executed and `:tool/calls` is trimmed to match so the assistant
   tool-calling message and the tool-result messages stay paired (OpenAI
   requires every tool_call to have a matching tool result). The dropped
   count is recorded as `:agent/tool-calls-dropped` so the CLI/logs can
   surface it."
  []
  {:name ::dispatch-tools
   :slot :tools
   :enter (fn [ctx]
            (let [loop-opts      (:agent/loop-opts ctx)
                  max-per-turn   (:max-tool-calls-per-turn loop-opts)
                  all-calls      (or (:tool/calls ctx) [])
                  registry       (or (:agent/tool-registry ctx) {})
                  capped-calls   (if (and max-per-turn (> (count all-calls) max-per-turn))
                                   (take max-per-turn all-calls)
                                   all-calls)
                  dropped-count  (- (count all-calls) (count capped-calls))
                  results        (tool/execute-tools registry ctx capped-calls)
                  ctx'           (-> ctx
                                     (assoc :tool/calls capped-calls
                                            :tool/results results)
                                     (update :agent/all-tool-results (fnil into []) results))
                  any-unavailable? (some (fn [r]
                                           (str/starts-with? (str (:result r)) "Tool '"))
                                         results)]
              (cond-> (if any-unavailable?
                        (update-in ctx' [:llm/request :messages]
                                   conj {:role "system"
                                         :content (str "One or more requested tools are not available. "
                                                       "Available tools: "
                                                       (str/join ", " (sort (keys registry))))})
                        ctx')
                (pos? dropped-count) (assoc :agent/tool-calls-dropped dropped-count))))})

(defn compose-tool-results-interceptor
  "`:tools` interceptor that appends the assistant tool-calling message
   and matching tool-result messages to `:llm/request :messages` for the
   follow-up turn."
  []
  {:name ::compose-tool-results
   :slot :tools
   :enter (fn [ctx]
            (let [results (or (:tool/results ctx) [])
                  assistant-msg (assistant-tool-message ctx)
                  result-msgs (mapv tool-result-message results)
                  new-msgs (if assistant-msg
                             (cons assistant-msg result-msgs)
                             result-msgs)]
              (update-in ctx [:llm/request :messages] into new-msgs)))})

(defn bump-loop-depth-interceptor
  "Interceptor that increments `:agent/tool-loop-depth`."
  []
  {:name ::bump-loop-depth
   :enter (fn [ctx]
            (update ctx :agent/tool-loop-depth (fnil inc 0)))})

(defn- tool-call-sig
  "A stable signature for the current set of tool calls, used for
   stall detection. Compares tool name + raw arguments JSON."
  [calls]
  (mapv (fn [c] {(get-in c [:function :name])
                 (get-in c [:function :arguments])})
        calls))

(defn tool-loop-interceptor
  "`:finalize` interceptor that asks the `loop` strategy whether to
   continue and, if so, enqueues the follow-up chain. The registry is
   read from `:agent/tool-registry` on each turn so the same loop works
   even if the registry is injected late. Stall detection: when the
   model emits the SAME set of tool calls as the previous turn, the
   loop does NOT enqueue again — `ensure-text-response-interceptor`
   then coaxes a textual summary instead of looping forever. Sets
   `:agent/loop-continuing?` so the adjacent `ensure-text-response`
   interceptor knows whether more LLM turns are coming (it cannot
   simply re-ask `-continue?`, because stall detection can refuse to
   enqueue even when `-continue?` is true)."
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
                (let [calls    (or (:tool/calls ctx) [])
                      sig      (tool-call-sig calls)
                      last-sig (:agent/last-tool-call-sig ctx)]
                  (if (= sig last-sig)
                    (assoc ctx :agent/loop-continuing? false :agent/stall-hit true)
                    (-> ctx
                        (assoc :agent/last-tool-call-sig sig
                               :agent/loop-continuing? true)
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
   enqueues another validation + LLM call + parse pass. Self-heal
   attempts are capped by `:agent/self-heal-attempts`."
  []
  {:name ::llm-call-with-self-heal
   :enter (fn [ctx]
            (let [attempts (get ctx :agent/self-heal-attempts 0)]
              (if (>= attempts max-self-heal-attempts)
                ctx
                (if-some [explain (humanize-request-errors (:llm/request ctx))]
                  (-> ctx
                      (repair-request-with-error explain)
                      (update :agent/self-heal-attempts (fnil inc 0))
                      chain/terminate
                      (chain/enqueue [(llm-call-with-self-heal)
                                      ix/llm-call
                                      ix/parse-response]))
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
  "Append a system message instructing the model to produce the final
   answer from the tool results already in the conversation, and STRIP
   :tools from the request so the model cannot escape back into
   tool-calling on the summary turn. Root-cause fix for the 2026-06-22
   empty-response bug: the summary call used to advertise all tools, so
   the model returned tool_calls (finish_reason tool_calls) instead of
   text. Used by ensure-text-response-interceptor when the loop stopped
   with a blank response but tool results exist."
  []
  {:name ::compose-summary-request
   :enter (fn [ctx]
            (-> ctx
                (update-in [:llm/request :messages]
                           conj {:role "system"
                                 :content "You have finished calling tools. Using the tool results above, produce the final answer for the user."})
                (update :llm/request dissoc :tools)))})

(defn compose-empty-retry-interceptor
  "Append a system message nudging the model to reply after it returned
   an empty response with no tool calls, and STRIP :tools so the model
   cannot escape into a tool call when forced to produce text. Used by
   ensure-text-response-interceptor on the first turn when the model
   produced nothing at all."
  []
  {:name ::compose-empty-retry
   :enter (fn [ctx]
            (-> ctx
                (update-in [:llm/request :messages]
                           conj {:role "system"
                                 :content "Your last response was empty. Reply to the user."})
                (update :llm/request dissoc :tools)))})

(defn ensure-text-response-interceptor
  "`:finalize` interceptor placed AFTER `tool-loop-interceptor`. When the
   loop is NOT continuing and :exchange/response is blank, enqueue a
   final mini-chain to coax a textual answer: a summary request when
   tool results exist, or an empty-response retry otherwise. Attempts
   are capped via counters :agent/summary-attempts (max
   max-summary-attempts) and :agent/empty-retry-attempts (max
   max-empty-retry-attempts). When the cap is exhausted, sets
   :agent/summary-failed? / :agent/empty-retry-failed? and returns ctx
   unchanged so the CLI fallback can surface a clear message. When a
   response is already present or the loop is still continuing, this
   interceptor is a no-op. The `loop` arg is accepted for symmetry with
   tool-loop-interceptor and ignored — the continue decision is read
   from :agent/loop-continuing? which tool-loop-interceptor sets."
  [loop]
  {:name ::ensure-text-response
   :slot :finalize
   :enter (fn [ctx]
            (let [summary-attempts (get ctx :agent/summary-attempts 0)
                  retry-attempts   (get ctx :agent/empty-retry-attempts 0)]
              (cond
                (not (str/blank? (:exchange/response ctx))) ctx
                (:agent/loop-continuing? ctx)               ctx
                ;; summary path: tools ran, response blank
                (and (seq (:agent/all-tool-results ctx))
                     (< summary-attempts max-summary-attempts))
                (-> ctx
                    (update :agent/summary-attempts (fnil inc 0))
                    (chain/enqueue [(compose-summary-request-interceptor)
                                    (llm-call-with-self-heal)
                                    ix/llm-call
                                    ix/parse-response
                                    (ensure-text-response-interceptor loop)]))
                (and (seq (:agent/all-tool-results ctx))
                     (>= summary-attempts max-summary-attempts))
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
