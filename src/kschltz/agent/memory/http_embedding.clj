(ns kschltz.agent.memory.http-embedding
  "OpenAI-compatible HTTP embedder.

   Calls a remote `/v1/embeddings` endpoint. Suitable for Ollama,
   OpenAI, text-embedding-api, and any other provider that speaks the
   OpenAI embeddings shape. This embedder has no native dependencies
   and is therefore native-image-friendly (unlike the LangChain4j
   in-process ONNX embedder).

   Required opts:
     :base-url    string   e.g. 'http://localhost:11434/v1'
     :model       string   e.g. 'nomic-embed-text'
     :dimensions  int      dimensionality of the returned vectors

   Optional opts:
     :api-key              string
     :connect-timeout-ms   int (default 10000)
     :request-timeout-ms   int (default 60000)"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [hato.client :as http]
            [kschltz.agent.memory.embedding :as embedding]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def default-connect-timeout-ms
  "Connect timeout (ms). Same default as the LLM HTTP client."
  10000)

(def default-request-timeout-ms
  "Request timeout (ms). Same default as the LLM HTTP client."
  60000)

(def HttpEmbedderOpts
  [:map
   [:base-url :string]
   [:model :string]
   [:dimensions [:int {:min 1}]]
   [:api-key {:optional true} [:maybe :string]]
   [:connect-timeout-ms {:optional true} :int]
   [:request-timeout-ms {:optional true} :int]])

(def EmbeddingRequest
  [:map
   [:base-url :string]
   [:model :string]
   [:text :string]
   [:api-key {:optional true} [:maybe :string]]
   [:connect-timeout-ms {:optional true} :int]
   [:request-timeout-ms {:optional true} :int]])

(defn- ->headers
  "Build HTTP headers for an embeddings request."
  [api-key]
  (cond-> {"Content-Type" "application/json"}
    api-key (assoc "Authorization" (str "Bearer " api-key))))

(defn- embeddings-url
  "Build the embeddings URL from the base URL. Accepts both conventions:
   base-url with or without a trailing /v1 segment."
  [base-url]
  (str base-url (if (str/ends-with? base-url "/v1")
                  "/embeddings"
                  "/v1/embeddings")))

(defn- error-response
  "Build an error ex-info with structured data about an HTTP failure."
  [kind {:keys [status body]}]
  (let [parsed (try (json/parse-string body true)
                    (catch Throwable _ body))]
    (ex-info (str "Embedding HTTP " kind " failed: " status)
             {:kind   kind
              :status status
              :body   parsed})))

(defn- post-embedding
  "POST `text` to the embeddings endpoint and return a vector of floats."
  [{:keys [base-url api-key model text connect-timeout-ms request-timeout-ms]
    :or   {connect-timeout-ms default-connect-timeout-ms
           request-timeout-ms default-request-timeout-ms}}]
  (let [url     (embeddings-url base-url)
        body    {:model model :input (or text "")}
        request {:method             :post
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
        (try
          (let [parsed    (json/parse-string (:body response) true)
                embedding (-> parsed :data first :embedding)]
            (if (seq embedding)
              (mapv float embedding)
              (throw (ex-info "Embedding HTTP response missing embedding data"
                              {:kind :parse
                               :body parsed}))))
          (catch clojure.lang.ExceptionInfo e
            (throw e))
          (catch Throwable t
            (throw (ex-info "Embedding HTTP response body is not valid JSON"
                            {:kind   :parse
                             :status status
                             :body   (:body response)
                             :cause  t}))))

        :else
        (throw (error-response :http-error response))))))

(defn http-embedder
  "Construct an `Embedder` that POSTs to an OpenAI-compatible
   `/v1/embeddings` endpoint."
  [{:keys [base-url model dimensions api-key
           connect-timeout-ms request-timeout-ms]
    :or   {connect-timeout-ms default-connect-timeout-ms
           request-timeout-ms default-request-timeout-ms}}]
  {:pre [(some? base-url) (some? model) (pos-int? dimensions)]}
  (reify embedding/Embedder
    (-embed [_ text]
      (post-embedding {:base-url           base-url
                       :api-key            api-key
                       :model              model
                       :text               text
                       :connect-timeout-ms connect-timeout-ms
                       :request-timeout-ms request-timeout-ms}))
    (-dimensions [_] dimensions)))

(m/=> post-embedding
      [:=> [:cat EmbeddingRequest] [:vector number?]])
(m/=> http-embedder
      [:=> [:cat HttpEmbedderOpts]
       [:fn #(satisfies? embedding/Embedder %)]])

(defn instrument!
  []
  (mi/instrument!
   {:filters [(mi/-filter-ns 'kschltz.agent.memory.http-embedding)]}))

(instrument!)
