(ns kschltz.agent.workbench.http
  "HTTP surface for the workbench: static CHAT|Portal UI + JSON/SSE API.
   Uses http-kit (available via the :workbench / :portal deps alias)."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.workbench.hub :as hub]
            [kschltz.agent.workbench.schemas :as schemas]))

(defn- json-response
  ([body] (json-response 200 body))
  ([status body]
   {:status  status
    :headers {"Content-Type"                "application/json; charset=utf-8"
              "Access-Control-Allow-Origin" "*"}
    :body    (json/generate-string body)}))

(defn- text-response
  [status content-type body]
  {:status  status
   :headers {"Content-Type"                 content-type
             "Access-Control-Allow-Origin"  "*"
             "Cache-Control"                "no-store, max-age=0"}
   :body    body})

(defn- read-json-body
  [req]
  (let [body (:body req)]
    (cond
      (nil? body) {}
      (string? body) (json/parse-string body true)
      :else (json/parse-stream (io/reader body) true))))

(defn- resource-bytes
  [path]
  (when-let [url (io/resource path)]
    (with-open [in (io/input-stream url)]
      (let [bout (java.io.ByteArrayOutputStream.)]
        (io/copy in bout)
        (.toByteArray bout)))))

(defn- static-file
  [rel]
  (let [path (str "kschltz/agent/workbench/" rel)
        bytes (resource-bytes path)
        ctype (cond
                (str/ends-with? rel ".html") "text/html; charset=utf-8"
                (str/ends-with? rel ".js")   "application/javascript; charset=utf-8"
                (str/ends-with? rel ".css")  "text/css; charset=utf-8"
                :else                        "application/octet-stream")]
    (if bytes
      (text-response 200 ctype bytes)
      (json-response 404 {:error "not found" :path rel}))))

(defn- parse-query
  [uri]
  (let [q (second (str/split (str uri) #"\?" 2))]
    (into {}
          (for [pair (when q (str/split q #"&"))
                :let [[k v] (str/split pair #"=" 2)]]
            [(keyword k) (or v "")]))))

(defn- sse-loop!
  [hub channel send! run? since]
  (try
    (loop [last-rev since]
      (if-not @run?
        nil
        (let [snap     (hub/snapshot hub)
              rev      (long (:rev snap 0))
              next-rev (if (> rev last-rev)
                         (do
                           (send! channel
                                  (str "data: " (json/generate-string snap) "\n\n")
                                  false)
                           rev)
                         last-rev)]
          (Thread/sleep 250)
          (recur next-rev))))
    (catch Throwable _)))

(defn- handle-sse
  "Long-poll SSE: emit snapshot whenever hub :rev advances past `since`."
  [hub req]
  (let [run?         (atom true)
        query        (parse-query (:uri req))
        since        (try (Long/parseLong (or (:since query) "0"))
                          (catch Exception _ 0))
        with-channel (requiring-resolve 'org.httpkit.server/with-channel)
        send!        (requiring-resolve 'org.httpkit.server/send!)
        on-close     (requiring-resolve 'org.httpkit.server/on-close)]
    (with-channel
      req
      (fn [channel]
        (on-close channel (fn [_] (reset! run? false)))
        (send! channel
               {:status  200
                :headers {"Content-Type"                "text/event-stream; charset=utf-8"
                          "Cache-Control"               "no-cache"
                          "Connection"                  "keep-alive"
                          "Access-Control-Allow-Origin" "*"}}
               false)
        (future (sse-loop! hub channel send! run? since))))))

(defn make-handler
  "Build a Ring-ish handler closed over `hub` and attach/submit callbacks.

   callbacks:
     :attach-selection!  (fn [] ref-or-nil)
     :on-message         optional (fn [msg] ...) after enqueue"
  [hub {:keys [attach-selection! on-message]}]
  (fn [req]
    (let [uri    (or (:uri req) "/")
          path   (first (str/split uri #"\?"))
          method (keyword (str/lower-case (name (or (:request-method req) :get))))]
      (try
        (case [method path]
          [:options path]
          {:status 204
           :headers {"Access-Control-Allow-Origin"  "*"
                     "Access-Control-Allow-Methods" "GET,POST,OPTIONS"
                     "Access-Control-Allow-Headers" "Content-Type"}
           :body ""}

          [:get "/"]
          (static-file "index.html")

          [:get "/app.js"]
          (static-file "app.js")

          [:get "/app.css"]
          (static-file "app.css")

          [:get "/api/state"]
          (json-response (hub/snapshot hub))

          [:get "/api/events"]
          (handle-sse hub req)

          [:post "/api/message"]
          (let [body (read-json-body req)
                msg  (schemas/decode-message
                      {:text (str (:text body))
                       :refs (vec (or (:refs body) []))})]
            (hub/enqueue-human! hub msg)
            (when on-message (on-message msg))
            (json-response {:ok true}))

          [:post "/api/attach-selection"]
          (let [ref (when attach-selection! (attach-selection!))]
            (if ref
              (json-response {:ok true :ref ref})
              (json-response 404 {:ok false :error "no portal selection"})))

          (json-response 404 {:error "not found" :path path}))
        (catch clojure.lang.ExceptionInfo e
          (json-response 400 {:error (ex-message e)
                              :data  (ex-data e)}))
        (catch Throwable t
          (json-response 500 {:error (ex-message t)}))))))

(defn start-server!
  "Start http-kit on host/port. Returns {:server :url :port :host}."
  [{:keys [host port handler]
    :or   {host "127.0.0.1" port 0}}]
  (let [run-server (requiring-resolve 'org.httpkit.server/run-server)
        stop!      (run-server handler {:ip host :port port})
        local-port (or (when (fn? stop!)
                         (:local-port (meta stop!)))
                       port)
        port*      (if (pos? (long (or local-port 0)))
                     local-port
                     port)]
    {:server stop!
     :host   host
     :port   port*
     :url    (str "http://" host ":" port*)}))

(defn stop-server!
  [server]
  (when server
    (try
      (server :timeout 100)
      (catch Throwable _
        (try (server) (catch Throwable _))))))
