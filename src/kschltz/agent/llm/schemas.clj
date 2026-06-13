(ns kschltz.agent.llm.schemas
  "Malli schemas for LLM HTTP request/response shapes.

   The shape is OpenAI-compatible (`/v1/chat/completions`). Other
   providers (Anthropic, local) require their own schemas; this MVP
   covers OpenAI-shaped endpoints only — Ollama, vLLM, OpenRouter,
   llama.cpp's server, etc. all expose this shape.

   These schemas are the network-boundary contract: anything
   leaving or entering the LLM HTTP layer passes through them.
   When `*instrument?*` is dynamic-var-bound true, the schemas
   decode both directions and throw on shape mismatch.

   Schema references:
   - OpenAI chat completions: https://platform.openai.com/docs/api-reference/chat
   - Anthropic messages:     (different shape; not covered here)
   - Ollama chat:            https://github.com/ollama/ollama/blob/main/docs/api.md#generate-a-chat-completion"
  (:require [malli.core :as m]))

(def ChatMessage
  "One message in a chat conversation. MVP subset: role + content."
  [:map
   [:role    [:enum "system" "user" "assistant" "tool"]]
   [:content :string]])

(def ChatRequest
  "Request body for POST /v1/chat/completions.

   MVP subset: model, messages, base-url/api-key/url are
   transport-layer concerns; the call site puts the latter on
   the http-client options, not in the body."
  [:map
   [:model    :string]
   [:messages [:vector ChatMessage]]
   [:stream   {:optional true} :boolean]
   [:temperature {:optional true} [:maybe :double]]
   [:max_tokens   {:optional true} [:maybe :int]]])

(def Choice
  "One assistant message returned by the model. `:content` is
   optional — some assistant messages are tool-calls only
   (content='' plus tool_calls)."
  [:map
   [:index           {:optional true} :int]
   [:message         [:map
                      [:role    [:= "assistant"]]
                      [:content {:optional true} :string]
                      [:tool_calls {:optional true}
                       [:vector [:map
                                 [:id   :string]
                                 [:type [:= "function"]]
                                 [:function [:map
                                            [:name :string]
                                            [:arguments :string]]]]]]]]
   [:finish_reason  {:optional true} :string]])

;; Usage is inlined as a closed map of optional fields. Malli
;; does not resolve `{:optional true} Usage` by name in the
;; closed-map schema; inlining is the documented workaround.
(def Usage
  "Token accounting (optional; some providers omit)."
  [:map
   [:prompt_tokens     {:optional true} :int]
   [:completion_tokens {:optional true} :int]
   [:total_tokens      {:optional true} :int]])

(def ChatResponse
  "Response body from POST /v1/chat/completions.

   MVP subset: choices (with message), model echo, usage (optional)."
  [:map
   [:id      {:optional true} :string]
   [:object  {:optional true} :string]
   [:created {:optional true} :int]
   [:model   :string]
   [:choices [:vector {:min 1} Choice]]
   [:usage   {:optional true} Usage]])

(defn shape-error
  "Build a Malli-shape error ex-info with structured data."
  [where value problems]
  (ex-info (str "LLM HTTP " (name where) " failed Malli validation")
           {:where   where
            :problems (vec problems)
            :value   (try (pr-str value)
                         (catch Throwable _ value))}))

(defn decode-request
  "Validate a request map against `ChatRequest`. Returns the
   validated map (or the input unchanged if schema passes) on
   success; throws ex-info with `:problems` on failure."
  [req]
  (if-let [problems (m/explain ChatRequest req)]
    (throw (shape-error :request req problems))
    req))

(defn decode-response
  "Validate a response map against `ChatResponse`. Returns the
   validated map on success; throws ex-info with `:problems` on
   failure. Use this in tests or when you want strict shape
   enforcement; the http-client uses a softer `extract-choices`
   that tolerates provider quirks."
  [resp]
  (if-let [problems (m/explain ChatResponse resp)]
    (throw (shape-error :response resp problems))
    resp))

(defn extract-text
  "Pull the assistant text out of a response. Returns the empty
   string if `choices[0].message.content` is missing."
  [resp]
  (or (get-in resp [:choices 0 :message :content]) ""))

(defn extract-tool-calls
  "Pull the tool calls out of a response. Returns [] if absent."
  [resp]
  (or (get-in resp [:choices 0 :message :tool_calls]) []))

(defn extract-model
  "Echo of the model that produced the response, or 'unknown'."
  [resp]
  (or (:model resp) "unknown"))

(defn extract-finish-reason
  "Why the model stopped; 'unknown' if absent."
  [resp]
  (or (get-in resp [:choices 0 :finish_reason]) "unknown"))
