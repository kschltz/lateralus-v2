(ns fake-mcp-http-server
  "Deterministic Streamable HTTP MCP server for tests and demos.

   Reuses `fake-mcp-server/handle-message` for JSON-RPC semantics.
   Supports:
     - application/json responses (default)
     - text/event-stream when query `?sse=1` or header Prefer: sse
     - Bearer auth when FAKE_MCP_TOKEN is set (optional)

   Launch:
     clojure -M:dev -m fake-mcp-http-server
   Prints the bound base URL (one line) to stdout."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [fake-mcp-server :as fake]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as resp]))

(defn- authorized?
  "When FAKE_MCP_TOKEN is set, require matching Bearer token."
  [req]
  (if-let [expected (not-empty (System/getenv "FAKE_MCP_TOKEN"))]
    (let [auth (or (get-in req [:headers "authorization"]) "")]
      (= auth (str "Bearer " expected)))
    true))

(defn- want-sse?
  "SSE is opt-in via Prefer: sse or ?sse=1. Do NOT key off Accept —
   Streamable HTTP clients must advertise both json and event-stream."
  [req]
  (or (= "1" (get-in req [:query-params "sse"]))
      (str/includes? (str/lower-case (str (get-in req [:headers "prefer"]))) "sse")
      ;; Allow tests to force SSE with a custom header.
      (= "1" (get-in req [:headers "x-fake-mcp-sse"]))))

(defn- read-json-body
  [req]
  (let [body (:body req)
        s (cond
            (string? body) body
            (nil? body) ""
            :else (slurp body))]
    (json/parse-string s true)))

(defn- sse-body
  [msg]
  (str "event: message\n"
       "data: " (json/generate-string msg) "\n\n"))

(defn handle-request
  "Ring handler for POST /mcp (and POST /)."
  [req]
  (cond
    (not= :post (:request-method req))
    (-> (resp/response "method not allowed")
        (resp/status 405))

    (not (authorized? req))
    (-> (resp/response (json/generate-string
                        {:jsonrpc "2.0"
                         :error {:code -32001 :message "unauthorized"}}))
        (resp/status 401)
        (resp/content-type "application/json"))

    :else
    (try
      (let [msg (read-json-body req)
            out (fake/handle-message msg)]
        (cond
          (nil? out)
          (-> (resp/response "")
              (resp/status 202))

          (want-sse? req)
          (-> (resp/response (sse-body out))
              (resp/status 200)
              (resp/content-type "text/event-stream"))

          :else
          (-> (resp/response (json/generate-string out))
              (resp/status 200)
              (resp/content-type "application/json"))))
      (catch Throwable t
        (-> (resp/response (json/generate-string
                            {:jsonrpc "2.0"
                             :error {:code -32700
                                     :message (ex-message t)}}))
            (resp/status 400)
            (resp/content-type "application/json"))))))

(defn start!
  "Start Jetty on `port` (0 = ephemeral). Returns
   `{:server :port :url :stop!}`."
  ([] (start! 0))
  ([port]
   (let [server (jetty/run-jetty
                 (fn [req]
                   ;; Normalize path: /mcp or /
                   (let [uri (:uri req)]
                     (if (or (= uri "/mcp") (= uri "/") (nil? uri))
                       (handle-request req)
                       (-> (resp/response "not found")
                           (resp/status 404)))))
                 {:port port :join? false :host "127.0.0.1"})
         bound (-> server .getURI .getPort)
         url (str "http://127.0.0.1:" bound "/mcp")]
     {:server server
      :port bound
      :url url
      :stop! (fn [] (.stop server))})))

(defn -main
  [& _]
  (let [{:keys [url]} (start! 0)]
    (println url)
    @(promise)))
