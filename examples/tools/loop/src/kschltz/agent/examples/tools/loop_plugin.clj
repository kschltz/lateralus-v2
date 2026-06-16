(ns kschltz.agent.examples.tools.loop-plugin
  "Example plugin that demonstrates lateralus plugin extensibility by
   implementing a complete OpenAI-shaped tool-calling loop.

   The plugin brings its own interceptor chain (via `:plugin/chain`),
   so the base exchange chain is left untouched. It registers two
   example tools (`time/now` and `calculator/eval`), injects their
   definitions into the LLM request, executes the calls the model
   returns, and loops back to the LLM with the results until the model
   produces a final text response.

   The loop is implemented with `chain/enqueue` inside a single
   exchange, so the runtime's `send-message` contract stays the same:
   one user prompt in, one final ctx out."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [malli.core :as m]
            [malli.error :as me]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.llm.schemas :as schemas]))

(def ^:private max-loop-depth
  "Safety cap on the number of back-to-back LLM calls inside one
   exchange. Prevents runaway tool loops with misbehaving models."
  5)

(defn- now-iso
  "Current UTC time as an ISO-8601 string."
  []
  (str (java.time.Instant/now)))

(defn- safe-calc
  "Evaluate a small whitelist of arithmetic expressions written as
   Clojure EDN, e.g. \"(+ 1 2 3)\". Anything outside `+ - * / max min`
   with numeric args is rejected."
  [expr-str]
  (let [form (edn/read-string expr-str)
        op (and (seq? form) (first form))
        nums (rest form)]
    (if (and (#{'+ '- '* '/ 'max 'min} op)
             (seq nums)
             (every? number? nums))
      (apply ({'+ + '- - '* * '/ /
               'max max 'min min} op)
             nums)
      (throw (ex-info "Unsupported calculator expression"
                      {:expr expr-str
                       :allowed-ops ["+" "-" "*" "/" "max" "min"]})))))

(def default-tools
  "Tool definitions and handlers used by the example."
  {:definitions
   [{:type "function"
     :function {:name "time/now"
                :description "Return the current UTC date and time as an ISO-8601 string."
                :parameters {:type "object"
                             :properties {}}}}
    {:type "function"
     :function {:name "calculator/eval"
                :description "Evaluate a simple arithmetic expression. Use prefix notation, e.g. (+ 1 2 3), (* 4 5), (/ 10 2)."
                :parameters {:type "object"
                             :required ["expression"]
                             :properties {"expression" {:type "string"
                                                        :description "Arithmetic expression in prefix notation."}}}}}]
   :handlers
   {"time/now" (fn [_args] (now-iso))
    "calculator/eval" (fn [{:keys [expression]}]
                        (str (safe-calc expression)))}})

(defn- merge-tools
  "Merge user-provided tool definitions/handlers over the defaults.
   Users can override existing tools or add new ones without
   redeclaring the whole registry."
  [user-tools]
  (let [defs (into (vec (:definitions default-tools))
                   (:definitions user-tools))
        handlers (merge (:handlers default-tools)
                        (:handlers user-tools))]
    {:definitions defs
     :handlers handlers}))

(defn- parse-arguments
  "Parse the JSON arguments string that the model returned."
  [arguments]
  (try (json/parse-string arguments true)
       (catch Throwable _ {})))

(defn- execute-tool
  "Look up the tool handler in `registry` and run it. Returns the
   handler result or `:not-implemented` when no handler is registered."
  [registry call]
  (let [name (get-in call [:function :name])
        args (parse-arguments (get-in call [:function :arguments]))
        handler (get-in registry [:handlers name])]
    (if handler
      (try (handler args)
           (catch Throwable t
             (str "tool error: " (ex-message t))))
      :not-implemented)))

(defn- dispatch-tools-interceptor
  "Interceptor that executes tool calls against the registry and sets
   `:tool/results`. Also accumulates every result in
   `:agent/all-tool-results` so the final ctx still records tools that
   ran in earlier loop iterations."
  [registry]
  {:name ::dispatch-tools
   :enter (fn [ctx]
            (let [calls (or (:tool/calls ctx) [])
                  results (mapv (fn [c]
                                  {:call c
                                   :result (execute-tool registry c)})
                                calls)]
              (-> ctx
                  (assoc :tool/results results)
                  (update :agent/all-tool-results into results))))})

(defn- assistant-tool-message
  "Build the assistant message that requested the tool calls.
   Must precede the matching tool-result messages in the chat history."
  [ctx]
  (let [calls (or (:tool/calls ctx) [])]
    (when (seq calls)
      {:role "assistant"
       :content (or (:exchange/response ctx) "")
       :tool_calls calls})))

(defn- tool-result-message
  "Build an OpenAI-shaped tool-result message from a single result."
  [{:keys [call result]}]
  {:role "tool"
   :tool_call_id (:id call)
   :content (str result)})

(defn- compose-tool-results-interceptor
  "Interceptor that appends the assistant tool-calling message and
   the matching tool-result messages to the current `:llm/request
   :messages`."
  []
  {:name ::compose-tool-results
   :enter (fn [ctx]
            (let [results (or (:tool/results ctx) [])
                  assistant-msg (assistant-tool-message ctx)
                  result-msgs (mapv tool-result-message results)
                  new-msgs (if assistant-msg
                             (cons assistant-msg result-msgs)
                             result-msgs)]
              (update-in ctx [:llm/request :messages] into new-msgs)))})

(defn- bump-loop-depth-interceptor
  "Interceptor that increments `:agent/tool-loop-depth`."
  []
  {:name ::bump-loop-depth
   :enter (fn [ctx]
            (update ctx :agent/tool-loop-depth (fnil inc 0)))})

(declare tool-loop-interceptor llm-call-with-self-heal)

(defn- tool-loop-interceptor
  "Interceptor that, after dispatch, decides whether to loop back to
   the LLM with tool results. Uses `chain/enqueue` to append a
   mini-chain for the follow-up call. Depth is capped at
   `max-loop-depth`."
  [registry max-depth]
  {:name ::tool-loop
   :enter (fn [ctx]
            (let [depth (get ctx :agent/tool-loop-depth 0)
                  results (or (:tool/results ctx) [])
                  implemented? (some #(not= :not-implemented (:result %)) results)]
              (if (and implemented? (< depth max-depth))
                (chain/enqueue ctx
                               [(bump-loop-depth-interceptor)
                                (compose-tool-results-interceptor)
                                (llm-call-with-self-heal)
                                ix/llm-call
                                ix/parse-response
                                (dispatch-tools-interceptor registry)
                                (tool-loop-interceptor registry max-depth)])
                ctx)))})

(defn- inject-tools-interceptor
  "Interceptor that adds the registered tool definitions to the
   outgoing `:llm/request`."
  [tool-definitions]
  {:name ::inject-tools
   :enter (fn [ctx]
            (let [req (:llm/request ctx)]
              (assoc ctx :llm/request (assoc req :tools tool-definitions))))})

(defn- trace-interceptor
  "Build an interceptor that prints a trace line before and after the
   named interceptor `ix` runs. Useful for diagnosing chain behavior."
  [name]
  (let [trace-name (keyword "tools-loop.trace" (str name))
        trace-fn (fn [stage ctx]
                   (let [resp (:exchange/response ctx)]
                     (println (format "[trace] %s %s | loop-depth=%s tool-calls=%s tool-results=%s response=%s"
                                      (pr-str name)
                                      stage
                                      (get ctx :agent/tool-loop-depth 0)
                                      (count (:tool/calls ctx))
                                      (count (:tool/results ctx))
                                      (pr-str (if (seq resp)
                                                (subs (str resp) 0 (min 40 (count (str resp))))
                                                ""))))))]
    {:name trace-name
     :enter (fn [ctx] (trace-fn :enter ctx) ctx)
     :leave (fn [ctx] (trace-fn :leave ctx) ctx)
     :error (fn [ctx ex]
              (println (format "[trace] %s error | %s" (pr-str name) (ex-message ex)))
              ctx)}))

(defn- trace-chain
  "Interleave trace interceptors between every interceptor in `chain`.
   The first interceptor still runs first, but a trace interceptor runs
   immediately before and after each original interceptor."
  [chain]
  (vec (mapcat (fn [ix]
                 [(trace-interceptor (:name ix)) ix])
               chain)))

(def ^:private max-self-heal-attempts
  "Cap on how many times we retry an LLM call after fixing a Malli
   validation error in the outgoing request."
  3)

(defn- humanize-request-errors
  "Return humanized Malli validation errors for `req` against
   `schemas/ChatRequest`, or nil when valid."
  [req]
  (some-> schemas/ChatRequest (m/explain req) (me/humanize)))

(defn- repair-request-with-error
  "Append a system message describing the validation error so the
   LLM can self-correct its next tool call."
  [ctx explain]
  (update-in ctx [:llm/request :messages]
             conj {:role "system"
                   :content (str "The request built from your last tool response failed schema validation. Fix the tool call format and try again. Errors: "
                                 (pr-str explain))}))

(defn- llm-call-with-self-heal
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
                  (do
                    (println (format "[self-heal] attempt %d request invalid: %s"
                                     (inc attempts) (pr-str explain)))
                    (println (format "[self-heal] failing request: %s"
                                     (pr-str (:llm/request ctx))))
                    (-> ctx
                        (repair-request-with-error explain)
                        (update :agent/self-heal-attempts (fnil inc 0))
                        chain/terminate
                        (chain/enqueue [(llm-call-with-self-heal)
                                        ix/llm-call
                                        ix/parse-response])))
                  ctx))))})

(defn- build-chain
  "Assemble the full exchange chain for the tool-calling plugin."
  [registry max-depth trace?]
  (cond-> [ix/error-boundary
           ix/compose-context
           (inject-tools-interceptor (:definitions registry))
           (llm-call-with-self-heal)
           ix/llm-call
           ix/parse-response
           (dispatch-tools-interceptor registry)
           (tool-loop-interceptor registry max-depth)
           ix/store-exchange
           ix/deliver-responses
           ix/notify]
    trace? trace-chain))

(defn loop-plugin
  "Construct the tool-calling loop plugin.

   `opts` keys:
     :tools     — optional map with `:definitions` (vector of OpenAI
                  Tool maps) and `:handlers` (map of name -> fn).
                  Merged over `default-tools`.
     :max-depth — cap on follow-up LLM calls (default 5).
     :trace?    — when true, print a trace line before/after each
                  interceptor in the plugin chain.

   Returns a plugin map with `:plugin/chain`, so it replaces the entire
   assembled chain when referenced as `:lateralus/exchange-chain`."
  ([] (loop-plugin {}))
  ([{:keys [tools max-depth trace?]
     :or   {tools {}
            max-depth max-loop-depth
            trace? false}}]
   (let [registry (merge-tools tools)]
     {:plugin/name :tools-loop
      :plugin/doc "Tool-calling loop example plugin."
      :plugin/chain (build-chain registry (or max-depth max-loop-depth) trace?)})))
