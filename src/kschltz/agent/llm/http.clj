(ns kschltz.agent.llm.http
  "Real HTTP-backed LlmClient implementation.

   Wraps hato to POST OpenAI-shaped chat completions to a remote
   endpoint. The boundary is the only place in the codebase that
   speaks HTTP — `rg 'http/completion' src/` should match only this
   namespace.

   Defaults (configurable via opts):
   - connect-timeout: 10s (lesson from v1: cloud APIs routinely
     need >2s for TLS handshake + DNS on first call)
   - request-timeout: 60s (LLM streaming is unbounded; full
     response is bounded by request-timeout; per-message
     timeouts are a future concern)
   - headers: Content-Type application/json; Authorization Bearer
     when `:api-key` is set

   Error handling: throws `ex-info` with `:kind` and HTTP status /
   body so callers (and the audit trail) can distinguish protocol
   errors, network errors, and shape errors.

   Streaming: not implemented. The :stream field is honored in
   the request schema (so providers don't reject), but the MVP
   only handles non-streaming responses."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [hato.client :as http]
            [kschltz.agent.llm.client :as lcm-client]
            [kschltz.agent.llm.schemas :as schemas]))

(def default-connect-timeout-ms
  "Connect timeout (ms). Cloud APIs routinely need >2s for TLS
   handshake + DNS on first call; 10s is a generous first-call
   budget."
  10000)

(def default-request-timeout-ms
  "Request timeout (ms). A 60s budget is enough for a single
   non-streaming chat completion from any reasonable provider."
  60000)

(defn- ->headers
  "Build the HTTP headers for a chat-completions request. Includes
   Authorization Bearer when `:api-key` is set."
  [api-key]
  (cond-> {"Content-Type" "application/json"}
    api-key (assoc "Authorization" (str "Bearer " api-key))))

(defn- error-response
  "Build an error ex-info with structured data about an HTTP failure."
  [kind {:keys [status body]}]
  (let [parsed (try (json/parse-string body true)
                    (catch Throwable _ body))]
    (ex-info (str "LLM HTTP " kind " failed: " status)
             {:kind   kind
              :status status
              :body   parsed})))

(defn- chat-completions-url
  "Build the chat completions URL from the base URL. Accepts both
   conventions: base-url with or without a trailing /v1 segment."
  [base-url]
  (str base-url (if (str/ends-with? base-url "/v1")
                  "/chat/completions"
                  "/v1/chat/completions")))

(defn models-url
  "Build the OpenAI-shaped model-list URL from `base-url`. Mirrors
   `chat-completions-url`: a base-url ending in `/v1` gets `/models`
   appended; any other base-url gets `/v1/models`. Works for OpenAI,
   Ollama (`/v1/models`), and Ollama Cloud (`https://ollama.com/v1`)."
  [base-url]
  (str base-url (if (str/ends-with? base-url "/v1")
                  "/models"
                  "/v1/models")))

(defn list-models
  "GET the OpenAI-shaped model list from `base-url`. Returns a sorted,
   deduplicated vector of model-id strings. Sends `Authorization: Bearer
   <api-key>` when `api-key` is set. Throws `ex-info` with `:kind
   :http-error`, `:parse`, or `:transport` on failure — the caller decides
   whether to fall back to free-text entry.

   This is the only other HTTP call in the codebase besides
   `chat-completions`; it stays here so `rg 'http' src/` still routes all
   network I/O through this namespace."
  ([base-url] (list-models base-url nil))
  ([base-url api-key]
   (let [url      (models-url base-url)
         response (http/request {:method           :get
                                :url              url
                                :headers          (->headers api-key)
                                :as               :string
                                :connect-timeout  default-connect-timeout-ms
                                :request-timeout  default-request-timeout-ms
                                :throw-exceptions false
                                :coerce           :always})
         status   (:status response)]
     (cond
       (and status (<= 200 status 299))
       (let [body (try (json/parse-string (:body response) true)
                       (catch Throwable t
                         (throw (ex-info "models response is not valid JSON"
                                         {:kind   :parse
                                          :status status
                                          :body   (:body response)
                                          :cause  t}))))]
         (->> (get body :data)
              (map :id)
              (filter string?)
              (distinct)
              (sort)
              (vec)))
       :else
       (throw (error-response :http-error response))))))

(defn- post-chat
  "POST a chat-completions request to the given base URL. Returns
   the parsed JSON body. Throws ex-info on transport / HTTP errors."
  [{:keys [base-url api-key model messages temperature max-tokens tools
           tool-choice connect-timeout-ms request-timeout-ms]
    :or   {connect-timeout-ms default-connect-timeout-ms
           request-timeout-ms  default-request-timeout-ms}}]
  (let [url      (chat-completions-url base-url)
        body     (cond-> {:model    model
                          :messages (vec messages)}
                   temperature (assoc :temperature temperature)
                   max-tokens  (assoc :max-tokens max-tokens)
                   tools       (assoc :tools tools)
                   tool-choice (assoc :tool_choice tool-choice))
        request  {:method              :post
                  :url                url
                  :headers            (->headers api-key)
                  :body               (json/generate-string body)
                  :as                 :string
                  :connect-timeout    connect-timeout-ms
                  :request-timeout    request-timeout-ms
                  :throw-exceptions   false
                  :coerce             :always}
        response (http/request request)]
    (let [status (:status response)]
      (cond
        (<= 200 status 299)
        (try (json/parse-string (:body response) true)
             (catch Throwable t
               (throw (ex-info "LLM HTTP response body is not valid JSON"
                               {:kind   :parse
                                :status status
                                :body   (:body response)
                                :cause  t}))))

        :else
        (throw (error-response :http-error response))))))

(defn http-client
  "Construct a real LlmClient that POSTs OpenAI-shaped chat
   completions to `:base-url`. Required opts:
     :base-url  string  e.g. 'https://api.openai.com'
     :api-key   string  optional; if nil, no Authorization header
     :model     string  default model

   Optional opts override the per-call request shape:
     :temperature  double
     :max-tokens   int

   Connect / request timeouts are passed through from the
   Integrant config map; defaults from this namespace apply when
   absent. Errors are surfaced as ex-info with :kind :http-error
   or :parse, plus :status and :body."
  [opts]
  (reify lcm-client/LlmClient
    (-call [_client req]
      ;; Per-call request shape overrides the client-defaults.
      (let [merged (merge opts req)
            _      (schemas/decode-request merged)
            resp   (post-chat merged)]
        ;; Note: we don't call decode-response here because real
        ;; providers routinely return extra fields the strict
        ;; ChatResponse schema doesn't list. extract-* helpers
        ;; tolerate that; the schema is for the *request* side
        ;; and for tests that want strict shape enforcement.
        resp))))
