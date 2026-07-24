(ns kschltz.agent.tools.mcp.http
  "Streamable HTTP `McpTransport` for remote MCP servers.

   Each JSON-RPC message is one HTTP POST to the MCP endpoint. Responses
   may be `application/json` or `text/event-stream` (SSE). Notifications
   expect HTTP 202.

   Auth v1: optional Bearer token + arbitrary headers. OAuth deferred.

   Fits the existing duplex `McpTransport` by posting on `-send!` and
   enqueueing decoded JSON-RPC responses for `-recv!`."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [hato.client :as hato]
            [kschltz.agent.tools.mcp.protocol :as proto]
            [kschltz.agent.tools.mcp.url :as url]
            [malli.core :as m]
            [malli.instrument :as mi])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]
           [java.util.concurrent.atomic AtomicBoolean]))

(def protocol-version-header
  "MCP-Protocol-Version header value for Streamable HTTP POSTs.
   Matches the initialize protocolVersion our client negotiates."
  "2024-11-05")

(defn- raise
  [phase msg data]
  (throw (ex-info msg (merge {:phase phase} data))))

(defn- resolve-bearer
  "Return a bearer token from `:bearer-token` or `:bearer-token-env`."
  [{:keys [bearer-token bearer-token-env]}]
  (or (not-empty bearer-token)
      (when (not-empty bearer-token-env)
        (not-empty (System/getenv bearer-token-env)))))

(defn build-headers
  "HTTP headers for a Streamable HTTP POST, including optional Bearer."
  [server-cfg]
  (let [bearer (resolve-bearer server-cfg)
        extra (or (:headers server-cfg) {})]
    (cond-> (merge
             {"Content-Type" "application/json"
              "Accept" "application/json, text/event-stream"
              "MCP-Protocol-Version" protocol-version-header}
             extra)
      bearer (assoc "Authorization" (str "Bearer " bearer)))))

(defn parse-sse-data
  "Extract JSON-RPC message maps from an SSE body. Returns a vector of
   parsed maps (data: lines only). Ignores malformed data lines."
  [body]
  (let [lines (str/split-lines (or body ""))]
    (persistent!
     (reduce
      (fn [acc line]
        (if (str/starts-with? line "data:")
          (let [payload (str/trim (subs line 5))]
            (if (str/blank? payload)
              acc
              (try
                (conj! acc (json/parse-string payload true))
                (catch Throwable _ acc))))
          acc))
      (transient [])
      lines))))

(defn- decode-response-body
  "Decode an HTTP response body into zero or more JSON-RPC maps."
  [{:keys [headers body]}]
  (let [ct (str/lower-case
            (str (or (get headers "content-type")
                     (get headers "Content-Type")
                     "")))
        body-str (cond
                   (string? body) body
                   (nil? body) ""
                   :else (slurp body))]
    (cond
      (str/includes? ct "text/event-stream")
      (parse-sse-data body-str)

      (str/blank? body-str)
      []

      :else
      (try
        [(json/parse-string body-str true)]
        (catch Throwable t
          (raise :protocol
                 (str "MCP HTTP JSON parse failed: " (ex-message t))
                 {:body body-str}))))))

(defn- default-http-fn
  [req]
  (hato/request (assoc req :throw-exceptions false :as :string)))

(defn post-message!
  "POST one JSON-RPC `message` to `url`. Returns decoded message maps
   (empty for accepted notifications). Raises `:phase :http` / `:timeout`
   / `:auth` / `:protocol` on failure.

   `http-fn` is `(fn [req] → hato-shaped {:status :headers :body})`."
  [url headers message timeout-ms http-fn]
  (let [http-fn (or http-fn default-http-fn)
        notification? (nil? (:id message))
        req {:method :post
             :url url
             :headers headers
             :body (json/generate-string (assoc message :jsonrpc "2.0"))
             :timeout timeout-ms
             :connect-timeout timeout-ms}]
    (let [resp (try
                 (http-fn req)
                 (catch java.net.SocketTimeoutException t
                   (raise :timeout (str "MCP HTTP timeout: " (ex-message t))
                          {:url url :cause t}))
                 (catch Throwable t
                   (raise :http (str "MCP HTTP request failed: " (ex-message t))
                          {:url url :cause t})))
          status (:status resp)]
      (cond
        (and notification? (#{202 204} status))
        []

        (and notification? (<= 200 status 299))
        []

        (= status 401)
        (raise :auth "MCP HTTP unauthorized (401)"
               {:url url :status status :body (:body resp)})

        (= status 403)
        (raise :auth "MCP HTTP forbidden (403)"
               {:url url :status status :body (:body resp)})

        (not (<= 200 status 299))
        (raise :http (str "MCP HTTP status " status)
               {:url url :status status :body (:body resp)})

        :else
        (let [msgs (decode-response-body resp)]
          (when (and (not notification?) (empty? msgs))
            (raise :protocol "MCP HTTP response had no JSON-RPC message"
                   {:url url :status status}))
          msgs)))))

(defn connect-http!
  "Build a Streamable HTTP `McpTransport` for `server-cfg` (must include
   `:url`). Validates the URL, then returns a transport."
  [server-cfg]
  (let [url (url/assert-mcp-url! (:url server-cfg) server-cfg)
        headers (build-headers server-cfg)
        timeout (long (or (:request-timeout-ms server-cfg) 30000))
        http-fn (or (:http-fn server-cfg) default-http-fn)
        closed? (AtomicBoolean. false)
        inbound (LinkedBlockingQueue.)]
    (reify proto/McpTransport
      (-send! [_ message]
        (when (.get closed?)
          (raise :closed "MCP HTTP transport is closed" {:url url}))
        (let [msgs (post-message! url headers message timeout http-fn)]
          (doseq [m msgs]
            (.put inbound m))))
      (-recv! [_ timeout-ms]
        (when (.get closed?)
          (raise :closed "MCP HTTP transport is closed" {:url url}))
        (let [msg (.poll inbound (long (or timeout-ms timeout)) TimeUnit/MILLISECONDS)]
          (when (nil? msg)
            (raise :timeout "MCP HTTP recv timed out" {:url url}))
          msg))
      (-close-transport! [_]
        (.set closed? true)
        (.clear inbound))
      (-alive? [_]
        (not (.get closed?))))))

(m/=> build-headers
      [:=> [:cat :map] [:map-of :string :string]])

(m/=> parse-sse-data
      [:=> [:cat [:maybe :string]] [:vector :map]])

(m/=> connect-http!
      [:=> [:cat :map] :any])

(mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.tools.mcp.http)]})
