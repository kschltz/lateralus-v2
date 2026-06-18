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
     :finalize — decide whether to loop back to the LLM

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
         llm-call-with-self-heal dispatch-tools-interceptor tool-loop-interceptor)

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
     (tool-loop-interceptor this)]))

(defn react-loop
  "Construct a `ReActLoop` with an optional `max-depth` (default 5)."
  ([] (react-loop max-loop-depth))
  ([max-depth] (->ReActLoop max-depth)))

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
   that ran in earlier loop iterations."
  []
  {:name ::dispatch-tools
   :slot :tools
   :enter (fn [ctx]
            (let [calls    (or (:tool/calls ctx) [])
                  registry (or (:agent/tool-registry ctx) {})
                  results  (tool/execute-tools registry ctx calls)]
              (-> ctx
                  (assoc :tool/results results)
                  (update :agent/all-tool-results (fnil into []) results))))})

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

(defn tool-loop-interceptor
  "`:finalize` interceptor that asks the `loop` strategy whether to
   continue and, if so, enqueues the follow-up chain. The registry is
   read from `:agent/tool-registry` on each turn so the same loop works
   even if the registry is injected late."
  [loop]
  {:name ::tool-loop
   :slot :finalize
   :enter (fn [ctx]
            (if (-continue? loop ctx)
              (chain/enqueue ctx (-follow-up-chain loop (or (:agent/tool-registry ctx) {})))
              ctx))})

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
