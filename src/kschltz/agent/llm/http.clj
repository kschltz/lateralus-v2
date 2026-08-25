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
            [kschltz.agent.llm.schemas :as schemas]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def default-connect-timeout-ms
  "Connect timeout (ms). Cloud APIs routinely need >2s for TLS
   handshake + DNS on first call; 10s is a generous first-call
   budget."
  10000)

(def default-request-timeout-ms
  "Request timeout (ms). A 60s budget is enough for a single
   non-streaming chat completion from any reasonable provider."
  60000)

(def HttpCallOpts
  [:map
   [:base-url :string]
   [:model :string]
   [:messages [:sequential :map]]
   [:api-key {:optional true} [:maybe :string]]
   [:temperature {:optional true} number?]
   [:max-tokens {:optional true} :int]
   [:tools {:optional true} [:sequential :map]]
   [:tool-choice {:optional true} :any]
   [:connect-timeout-ms {:optional true} :int]
   [:request-timeout-ms {:optional true} :int]])

(def HttpClientOpts
  [:map
   [:base-url :string]
   [:model {:optional true} :string]
   [:api-key {:optional true} [:maybe :string]]
   [:temperature {:optional true} number?]
   [:max-tokens {:optional true} :int]
   [:connect-timeout-ms {:optional true} :int]
   [:request-timeout-ms {:optional true} :int]])

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

(defn normalize-base-url
  "Strip trailing slashes so `…/v1/` and `…/v1` resolve the same.
   Without this, `models-url` turns `http://localhost:11434/v1/` into
   `…/v1//v1/models` and Ollama answers 307 — local model list looks broken."
  [base-url]
  (str/replace (str base-url) #"/+$" ""))

(defn resolve-base-url
  "Normalize `base-url`, and when `LATERALUS_IN_DOCKER=1` rewrite
   `localhost`/`127.0.0.1:11434` to the compose Ollama service
   (`LATERALUS_DOCKER_OLLAMA_URL`, default `http://ollama:11434/v1`).

   Host-oriented profiles shared into the container otherwise fail
   model-list/chat with an empty error (nothing listens on container
   localhost:11434).

   Arity-2 `getenv` is `(fn [name] → string|nil)` for tests."
  ([base-url]
   (resolve-base-url base-url #(System/getenv %)))
  ([base-url getenv]
   (let [base (normalize-base-url base-url)
         in-docker? (= "1" (getenv "LATERALUS_IN_DOCKER"))
         docker-ollama (normalize-base-url
                        (or (not-empty (getenv "LATERALUS_DOCKER_OLLAMA_URL"))
                            "http://ollama:11434/v1"))]
     (if (and in-docker?
              (re-find #"(?i)(?:localhost|127\.0\.0\.1):11434" base))
       docker-ollama
       base))))

(defn- chat-completions-url
  "Build the chat completions URL from the base URL. Accepts both
   conventions: base-url with or without a trailing /v1 segment."
  [base-url]
  (let [base (normalize-base-url base-url)]
    (str base (if (str/ends-with? base "/v1")
                "/chat/completions"
                "/v1/chat/completions"))))

(defn models-url
  "Build the OpenAI-shaped model-list URL from `base-url`. Mirrors
   `chat-completions-url`: a base-url ending in `/v1` gets `/models`
   appended; any other base-url gets `/v1/models`. Works for OpenAI,
   Ollama (`/v1/models`), and Ollama Cloud (`https://ollama.com/v1`)."
  [base-url]
  (let [base (normalize-base-url base-url)]
    (str base (if (str/ends-with? base "/v1")
                "/models"
                "/v1/models"))))

(defn- ollama-native-root
  "Host root for native Ollama routes (`/api/tags`), stripping a trailing `/v1`."
  [base-url]
  (str/replace (normalize-base-url base-url) #"/v1$" ""))

(defn- parse-openai-model-ids
  [body]
  (->> (get body :data)
       (map :id)
       (filter string?)
       (distinct)
       (sort)
       (vec)))

(defn- parse-ollama-tag-ids
  "Native `/api/tags` body → model name strings."
  [body]
  (->> (get body :models)
       (map #(or (:name %) (:model %)))
       (filter string?)
       (distinct)
       (sort)
       (vec)))

(defn- get-json
  "GET `url` as JSON. Returns parsed body on 2xx; throws ex-info otherwise."
  [url api-key]
  (let [response (http/request {:method           :get
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
      (try (json/parse-string (:body response) true)
           (catch Throwable t
             (throw (ex-info "models response is not valid JSON"
                             {:kind   :parse
                              :status status
                              :body   (:body response)
                              :cause  t}))))
      :else
      (throw (error-response :http-error response)))))

(defn- list-models-openai
  [base-url api-key]
  (parse-openai-model-ids
   (get-json (models-url (resolve-base-url base-url)) api-key)))

(defn- list-models-ollama-tags
  "List pulled models via native Ollama `/api/tags` (no Bearer needed)."
  [base-url]
  (parse-ollama-tag-ids
   (get-json (str (ollama-native-root (resolve-base-url base-url)) "/api/tags")
             nil)))

(def ^:private ollama-cloud-base-url
  "OpenAI-compatible Ollama Cloud base URL."
  "https://ollama.com/v1")

(defn- local-ollama-base?
  "True for any OpenAI-style base aimed at an Ollama daemon on :11434
   (localhost, 127.0.0.1, host.docker.internal, compose service name, …)."
  [base-url]
  (boolean (re-find #"(?i):11434(?:/|$)" (normalize-base-url base-url))))

(defn- ollama-cloud-base?
  [base-url]
  (boolean (re-find #"(?i)(?:^https?://)?(?:www\.)?ollama\.com(?:/|$)"
                    (normalize-base-url base-url))))

(defn list-models
  "GET the OpenAI-shaped model list from `base-url`. Returns a sorted,
   deduplicated vector of model-id strings. Sends `Authorization: Bearer
   <api-key>` when `api-key` is set. Throws `ex-info` with `:kind
   :http-error`, `:parse`, or `:transport` on failure — the caller decides
   whether to fall back to free-text entry.

   For a local Ollama gateway (`*:11434`), falls back to native `/api/tags`
   when `/v1/models` fails (trailing-slash redirects, compat quirks).

   This is the only other HTTP call in the codebase besides
   `chat-completions`; it stays here so `rg 'http' src/` still routes all
   network I/O through this namespace."
  ([base-url] (list-models base-url nil))
  ([base-url api-key]
   (try
     (list-models-openai base-url api-key)
     (catch Exception e
       (if (local-ollama-base? base-url)
         (try (list-models-ollama-tags base-url)
              (catch Throwable _ (throw e)))
         (throw e))))))

(defn- resolve-ollama-api-key
  [api-key]
  (or (when-not (str/blank? (str api-key)) (str api-key))
      (System/getenv "OLLAMA_API_KEY")))

(defn- as-local-cloud-id
  "Local Ollama gateway expects cloud models as `name:cloud`."
  [id]
  (let [s (str id)]
    (if (str/ends-with? s ":cloud") s (str s ":cloud"))))

(defn- model-menu-key
  "Sort key for the CLI picker: local chat models first, then `:cloud`
   ids, then embedders — so Enter does not default to a remote model
   that may be unavailable when Ollama Cloud is disabled."
  [id]
  (let [s (str id)]
    [(cond
       (re-find #"(?i)embed" s) 2
       (str/ends-with? s ":cloud") 1
       :else 0)
     s]))

(defn merge-ollama-model-lists
  "Merge local + cloud model ids. When `cloud-suffix?` is true, cloud-only
   ids are rewritten to `name:cloud` for the local Ollama gateway.
   Ordering prefers local non-cloud chat models first."
  [local-ids cloud-ids {:keys [cloud-suffix?] :or {cloud-suffix? false}}]
  (let [local  (filter string? local-ids)
        cloud  (filter string? cloud-ids)
        tagged (if cloud-suffix? (map as-local-cloud-id cloud) cloud)]
    (->> (concat local tagged)
         distinct
         (sort-by model-menu-key)
         vec)))

(defn preferred-default-model
  "Default Enter selection: first non-`:cloud`, non-embedder id when present."
  [ids]
  (let [xs (filter string? ids)]
    (or (first (remove #(or (str/ends-with? % ":cloud")
                            (re-find #"(?i)embed" %))
                       xs))
        (first (remove #(re-find #"(?i)embed" %) xs))
        (first xs))))

(defn- merge-cloud-into-local?
  "Cloud catalog merge is for host CLI convenience. Inside Docker it
   drowns the pulled-local list (and OLLAMA_API_KEY is often set for
   unrelated cloud profiles). Opt in with LATERALUS_LIST_CLOUD=1."
  [base-url api-key]
  (let [key (resolve-ollama-api-key api-key)
        in-docker? (= "1" (System/getenv "LATERALUS_IN_DOCKER"))
        force? (= "1" (System/getenv "LATERALUS_LIST_CLOUD"))]
    (and (local-ollama-base? base-url)
         (not (str/blank? key))
         (or force? (not in-docker?)))))

(defn list-models-thorough
  "Like `list-models`, but when `base-url` is a local Ollama gateway and an
   Ollama Cloud API key is available (`api-key` or env `OLLAMA_API_KEY`),
   also merges the full cloud catalog as `name:cloud` ids.

   Local `/v1/models` only returns pulled models (often a handful). Cloud
   `/v1/models` returns the whole hosted catalog — this makes the CLI
   picker useful for Ollama Cloud without forcing `--base-url https://ollama.com/v1`.

   Skipped automatically when `LATERALUS_IN_DOCKER=1` unless
   `LATERALUS_LIST_CLOUD=1`."
  ([base-url] (list-models-thorough base-url nil))
  ([base-url api-key]
   (let [base    (resolve-base-url base-url)
         primary (list-models base api-key)
         key     (resolve-ollama-api-key api-key)]
     (if (or (ollama-cloud-base? base)
             (not (merge-cloud-into-local? base api-key)))
       (->> primary (sort-by model-menu-key) vec)
       (let [cloud (try (list-models ollama-cloud-base-url key)
                        (catch Throwable _ []))]
         (merge-ollama-model-lists primary cloud {:cloud-suffix? true}))))))

(defn- post-chat
  "POST a chat-completions request to the given base URL. Returns
   the parsed JSON body. Throws ex-info on transport / HTTP errors."
  [{:keys [base-url api-key model messages temperature max-tokens tools
           tool-choice connect-timeout-ms request-timeout-ms]
    :or   {connect-timeout-ms default-connect-timeout-ms
           request-timeout-ms  default-request-timeout-ms}}]
  (let [url      (chat-completions-url (resolve-base-url base-url))
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
        (try (-> (:body response)
                 (json/parse-string true)
                 schemas/decode-response)
             (catch clojure.lang.ExceptionInfo e
               (throw e))
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
        resp))))

(m/=> get-json
      [:=> [:cat :string [:maybe :string]] :map])
(m/=> list-models
      [:function
       [:=> [:cat :string] [:vector :string]]
       [:=> [:cat :string [:maybe :string]] [:vector :string]]])
(m/=> list-models-thorough
      [:function
       [:=> [:cat :string] [:vector :string]]
       [:=> [:cat :string [:maybe :string]] [:vector :string]]])
(m/=> post-chat
      [:=> [:cat HttpCallOpts] schemas/ChatResponse])
(m/=> http-client
      [:=> [:cat HttpClientOpts] :any])

(defn instrument!
  []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.llm.http)]}))

(instrument!)
