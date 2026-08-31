(ns kschltz.agent.workbench.http
  "HTTP surface for the workbench: static CHAT|Portal UI + JSON/SSE API.
   Uses http-kit (available via the :workbench / :portal deps alias).

   Portal is mounted on the same origin/port as CHAT so remote viewers
   (Tailscale MagicDNS, LAN) only need :7860 — the iframe does not depend
   on a separately published :7870."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.stream.bus :as stream.bus]
            [kschltz.agent.stream.protocol :as stream]
            [kschltz.agent.workbench.hub :as hub]
            [kschltz.agent.workbench.schemas :as schemas]
            [kschltz.agent.workbench.session-http :as session-http]
            [org.httpkit.server :as http-kit]))
  ;; http-kit is on the classpath only via the :workbench / :portal alias, which
  ;; is also the only path that loads this namespace (system.clj requires it
  ;; lazily via `requiring-resolve`). `with-channel` is a MACRO and cannot be
  ;; `requiring-resolve`d + called as a function — it must be required at
  ;; compile time and invoked as a macro, so we require http-kit here.

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

(defn- turn-id-from-path
  "Extract turn id from `/turn/<id>` or `/api/turns/<id>[/events]`."
  [path]
  (when-let [m (re-matches #"/turn/([^/]+)" (str path))]
    (second m)))

(defn- api-turn-id
  [path]
  (when-let [m (re-matches #"/api/turns/([^/]+)(?:/events)?" (str path))]
    (second m)))

(defn- turn-events-path?
  [path]
  (boolean (re-matches #"/api/turns/[^/]+/events" (str path))))

(defn- stream-bus-of
  [hub]
  (:stream-bus hub))

(defn request-hostname
  "Hostname from the Ring/http-kit Host header (port stripped).
   Supports `host:port` and `[ipv6]:port` forms."
  [req]
  (let [host-hdr (or (get-in req [:headers "host"])
                     (get-in req [:headers "Host"]))]
    (when-let [h (not-empty (str/trim (str host-hdr)))]
      (cond
        (str/starts-with? h "[")
        (or (second (re-find #"^\[([^\]]+)\]" h)) h)

        ;; Keep IPv4 / names; strip trailing :port when present.
        (re-find #":\d+$" h)
        (second (re-find #"^(.*):\d+$" h))

        :else h))))

(defn request-origin
  "Scheme://host[:port] as the browser used to reach CHAT."
  [req]
  (let [host-hdr (or (get-in req [:headers "host"])
                     (get-in req [:headers "Host"]))
        proto    (or (not-empty (get-in req [:headers "x-forwarded-proto"]))
                     (not-empty (get-in req [:headers "X-Forwarded-Proto"]))
                     "http")]
    (when (not-empty host-hdr)
      (str proto "://" (str/trim host-hdr)))))

(defn portal-session-id
  "Extract Portal's bare session UUID from a Portal URL
   (`http://host:port?<uuid>`)."
  [portal-url]
  (when (not-empty portal-url)
    (try
      (let [uri (java.net.URI. (str portal-url))
            q   (.getRawQuery uri)]
        (when (not-empty q)
          (str (java.util.UUID/fromString q))))
      (catch Exception _
        nil))))

(defn- bare-uuid-query?
  [q]
  (boolean
   (try
     (when (not-empty q)
       (java.util.UUID/fromString q)
       true)
     (catch Exception _
       false))))

(defn portal-session-query?
  "True when the request carries a bare Portal session UUID.
   http-kit usually puts it in `:query-string` (not embedded in `:uri`)."
  ([uri]
   (bare-uuid-query? (second (str/split (str uri) #"\?" 2))))
  ([uri query-string]
   (or (bare-uuid-query? query-string)
       (portal-session-query? uri))))

(defn rewrite-url-host
  "Replace the host in `url` with `hostname`, keeping scheme/port/path/query."
  [url hostname]
  (if (or (str/blank? (str url)) (str/blank? (str hostname)))
    url
    (try
      (let [uri    (java.net.URI. (str url))
            scheme (or (.getScheme uri) "http")
            port   (.getPort uri)
            path   (let [p (.getRawPath uri)]
                     (if (str/blank? p) "" p))
            query  (.getRawQuery uri)
            frag   (.getRawFragment uri)]
        (str scheme "://" hostname
             (when (pos? port) (str ":" port))
             path
             (when query (str "?" query))
             (when frag (str "#" frag))))
      (catch Exception _
        url))))

(defn portal-url-for-request
  "Map a stored Portal URL onto the CHAT origin (same host+port the browser
   used). Portal is served from this process on :7860, so remote viewers do
   not need a published :7870."
  [portal-url req]
  (if-let [session (portal-session-id portal-url)]
    (if-let [origin (request-origin req)]
      (str origin "/?" session)
      portal-url)
    ;; Fallback: at least rewrite the host if session parse fails.
    (if-let [host (request-hostname req)]
      (rewrite-url-host portal-url host)
      portal-url)))

(defn- client-snapshot
  "Hub snapshot with portal-url rewritten for the calling browser."
  [hub req]
  (let [snap (hub/snapshot hub)]
    (if-let [purl (:portal-url snap)]
      (assoc snap :portal-url (portal-url-for-request purl req))
      snap)))

(defn- session-conflict-response
  [expected actual]
  (when (and expected (not= (str expected) (str actual)))
    (json-response 409 {:ok false
                        :error "active session changed — refresh and retry"
                        :expected-session-id expected
                        :actual-session-id actual})))

(defn- sse-loop!
  [hub channel send! run? since req]
  (try
    (loop [last-rev since]
      (if-not @run?
        nil
        (let [snap     (client-snapshot hub req)
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
  "Long-poll SSE: emit snapshot whenever hub :rev advances past `since`.

  `with-channel` is http-kit's async-channel macro; it MUST be called as a
  macro (required at compile time), not `requiring-resolve`d + invoked as a
  function — that never expands and throws
  `Wrong number of args (2) passed to: org.httpkit.server/with-channel`."
  [hub req]
  (let [run?  (atom true)
        query (parse-query (:uri req))
        since (try (Long/parseLong (or (:since query) "0"))
                   (catch Exception _ 0))]
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (http-kit/with-channel req channel
      (http-kit/on-close channel (fn [_] (reset! run? false)))
      (http-kit/send! channel
                      {:status  200
                       :headers {"Content-Type"                "text/event-stream; charset=utf-8"
                                 "Cache-Control"               "no-cache"
                                 "Connection"                  "keep-alive"
                                 "Access-Control-Allow-Origin" "*"}}
                      false)
      (future (sse-loop! hub channel http-kit/send! run? since req)))))

(defn- turn-sse-loop!
  [stream-bus turn-id channel send! run? since]
  (try
    (loop [last (long since)]
      (if-not @run?
        nil
        (let [chunk (stream.bus/events-since stream-bus turn-id last)
              next  (if (and chunk (seq (:events chunk)))
                      (do
                        (send! channel
                               (str "data: " (json/generate-string chunk) "\n\n")
                               false)
                        (long (:rev chunk last)))
                      last)]
          (when (and chunk (not (:live? chunk)) (empty? (:events chunk)))
            (send! channel
                   (str "data: " (json/generate-string (assoc chunk :done true)) "\n\n")
                   false)
            (send! channel "" true))
          (Thread/sleep 150)
          (recur next))))
    (catch Throwable _)))

(defn- handle-turn-sse
  [hub req turn-id]
  (let [bus   (stream-bus-of hub)
        run?  (atom true)
        query (merge (parse-query (:uri req))
                     (parse-query (str "?" (or (:query-string req) ""))))
        since (try (Long/parseLong (or (:since query) "-1"))
                   (catch Exception _ -1))]
    (if-not (and (stream/stream-bus? bus) (stream.bus/snapshot bus turn-id))
      (json-response 404 {:error "turn not found" :id turn-id})
      #_{:clj-kondo/ignore [:unresolved-symbol]}
      (http-kit/with-channel req channel
        (http-kit/on-close channel (fn [_] (reset! run? false)))
        (http-kit/send! channel
                        {:status  200
                         :headers {"Content-Type"                "text/event-stream; charset=utf-8"
                                   "Cache-Control"               "no-cache"
                                   "Connection"                  "keep-alive"
                                   "Access-Control-Allow-Origin" "*"}}
                        false)
        (future (turn-sse-loop! bus turn-id channel http-kit/send! run? since))))))

(def ^:private portal-asset-paths
  "Paths owned by djblue/portal's HTTP handler (mounted on the CHAT server)."
  #{"/main.js" "/rpc" "/icon.svg" "/load" "/submit" "/wait.js"})

(defn portal-path?
  "True when this request should be handled by Portal (not CHAT)."
  ([method path uri]
   (portal-path? method path uri nil))
  ([method path uri query-string]
   (or (contains? portal-asset-paths path)
       (str/starts-with? (str path) "/vendor")
       (and (= method :get)
            (= path "/")
            (portal-session-query? uri query-string))
       ;; Portal source maps
       (and (= method :get)
            (string? path)
            (str/ends-with? path ".map")))))

(defn- strip-portal-host-js
  "Remove `window.PORTAL_HOST = ...` so the UI talks to the page origin
   (CHAT :7860) instead of Portal's private bind address/port."
  [body]
  (cond
    (string? body)
    (str/replace body #"window\.PORTAL_HOST\s*=\s*\"[^\"]*\";?" "")

    (bytes? body)
    (let [s (String. ^bytes body java.nio.charset.StandardCharsets/UTF_8)]
      (.getBytes (strip-portal-host-js s) java.nio.charset.StandardCharsets/UTF_8))

    :else body))

(defn- html-response?
  [resp]
  (let [ct (or (get-in resp [:headers "Content-Type"])
               (get-in resp [:headers "content-type"])
               "")]
    (str/includes? (str/lower-case (str ct)) "text/html")))

(defn- call-portal-handler
  "Delegate to portal.runtime.jvm.server/handler when Portal is on the classpath."
  [req]
  (try
    (let [handler (requiring-resolve 'portal.runtime.jvm.server/handler)
          resp    (handler req)]
      (cond-> resp
        (and resp (html-response? resp) (some? (:body resp)))
        (update :body strip-portal-host-js)))
    (catch Throwable t
      (json-response 503 {:error "portal handler unavailable"
                          :detail (ex-message t)}))))

(defn make-handler
  "Build a Ring-ish handler closed over `hub` and attach/submit callbacks.

   callbacks:
     :attach-selection!  (fn [] ref-or-nil)
     :on-message         optional (fn [msg] ...) after enqueue

   Portal asset/RPC routes (`/rpc`, `/main.js`, `/?<session-uuid>`, …) are
   delegated to Portal's in-process handler so the iframe is same-origin."
  [hub {:keys [attach-selection! on-message session-ops settings-ops secret-ops]}]
  (fn [req]
    (let [uri    (or (:uri req) "/")
          path   (first (str/split uri #"\?"))
          qs     (or (:query-string req)
                     (second (str/split uri #"\?" 2)))
          method (keyword (str/lower-case (name (or (:request-method req) :get))))]
      (try
        (cond
          (portal-path? method path uri qs)
          (call-portal-handler
           ;; Portal's get-session-id reads :query-string or ? in :uri.
           (cond-> req
             (and (str/blank? (str (:query-string req))) (not-empty qs))
             (assoc :query-string qs)))

          (= method :options)
          {:status 204
           :headers {"Access-Control-Allow-Origin"  "*"
                     "Access-Control-Allow-Methods" "GET,POST,PATCH,DELETE,OPTIONS"
                     "Access-Control-Allow-Headers" "Content-Type"}
           :body ""}

          :else
          (or
           (when (str/starts-with? (str path) "/api/sessions")
             (session-http/handle method path (read-json-body req) session-ops))
           (when (str/starts-with? (str path) "/api/settings")
             (when settings-ops
               (cond
                 (and (= method :get) (= path "/api/settings"))
                 (json-response ((:view-fn settings-ops)))

                 (and (= method :post) (= path "/api/settings"))
                 (let [body*  (read-json-body req)
                       op    (cond-> (:op body*)
                               (map? (:op body*)) (update :op keyword))]
                   (locking hub
                     (let [expected (:session-id body*)
                           actual   (:session-id (hub/snapshot hub))]
                       (or (session-conflict-response expected actual)
                           (let [result ((:apply-fn settings-ops) op)]
                             (if (:ok result)
                               (json-response result)
                               (json-response 400 result)))))))

                 (and (= method :get) (= path "/api/settings/models"))
                 (let [q    (parse-query uri)
                       view ((:models-fn settings-ops)
                             {:base-url (:base-url q)
                              :api-key  (:api-key q)})]
                   (if (seq (:error view))
                     (json-response 400 view)
                     (json-response view))))))
           (when (str/starts-with? (str path) "/api/secrets")
             (when secret-ops
               (cond
                 (and (= method :get) (= path "/api/secrets"))
                 (json-response ((:view-fn secret-ops)))

                 (and (#{:put :post} method) (= path "/api/secrets"))
                 (let [op     (read-json-body req)
                       result ((:put-fn secret-ops) op)]
                   (if (:ok result)
                     (json-response result)
                     (json-response 400 result)))

                 (and (= method :delete) (= path "/api/secrets"))
                 (let [q      (parse-query uri)
                       result ((:delete-fn secret-ops) (:label q))]
                   (if (:ok result)
                     (json-response result)
                     (json-response 400 result)))

                 :else
                 (json-response 404 {:error "not found"}))))
           (cond
             (and (= method :get) (= path "/"))
             (static-file "index.html")

             (and (= method :get) (= path "/app.js"))
             (static-file "app.js")

             (and (= method :get) (= path "/app.css"))
             (static-file "app.css")

             (and (= method :get) (= path "/turn.js"))
             (static-file "turn.js")

             (and (= method :get) (= path "/turn.css"))
             (static-file "turn.css")

             (and (= method :get) (turn-id-from-path path))
             (static-file "turn.html")

             (and (= method :get) (= path "/api/state"))
             (json-response (client-snapshot hub req))

             (and (= method :get) (= path "/api/events"))
             (handle-sse hub req)

             (and (= method :get) (= path "/api/turns/current"))
             (let [bus (stream-bus-of hub)
                   id  (when (stream/stream-bus? bus)
                         (or (stream.bus/current-id bus)
                             (stream.bus/latest-id bus)))
                   snap (when (and id (stream/stream-bus? bus))
                          (stream.bus/snapshot bus id))]
               (if snap
                 (json-response snap)
                 (json-response 404 {:error "no turn yet"})))

             (and (= method :get) (turn-events-path? path))
             (handle-turn-sse hub req (api-turn-id path))

             (and (= method :get) (api-turn-id path))
             (let [bus (stream-bus-of hub)
                   id  (api-turn-id path)
                   snap (when (stream/stream-bus? bus)
                          (stream.bus/snapshot bus id))]
               (if snap
                 (json-response snap)
                 (json-response 404 {:error "turn not found" :id id})))

             (and (= method :post) (= path "/api/message"))
             (let [body (read-json-body req)
                   msg  (schemas/decode-message
                         {:text (str (:text body))
                          :refs (vec (or (:refs body) []))})]
               (locking hub
                 (let [expected (:session-id body)
                       actual   (:session-id (hub/snapshot hub))]
                   (or
                    (session-conflict-response expected actual)
                    (do
                      (hub/enqueue-human! hub msg)
                      (when on-message (on-message msg))
                      (json-response {:ok true}))))))

             (and (= method :post) (= path "/api/portal-event"))
             ;; 2-way loop: artifacts rendered by portal_submit POST
             ;; small JSON interaction events back here (same-origin
             ;; iframe). hub/portal-event! validates + enqueues them
             ;; as agent-visible input; errors surface as 400.
             (let [body (read-json-body req)
                   result (try
                            (hub/portal-event! hub (if (and (map? body) (contains? body :payload)) (:payload body) body))
                            (catch clojure.lang.ExceptionInfo e
                              {:ok false :error (.getMessage e)}))]
               (if (:ok result)
                 (json-response result)
                 (json-response 400 result)))

             (and (= method :post) (= path "/api/attach-selection"))
             (let [ref (when attach-selection! (attach-selection!))]
               (if ref
                 (json-response {:ok true :ref ref})
                 (json-response 404 {:ok false :error "no portal selection"})))

             :else
             (json-response 404 {:error "not found" :path path}))))
        (catch clojure.lang.ExceptionInfo e
          (json-response 400 {:error (ex-message e)
                              :data  (ex-data e)}))
        (catch Throwable t
          (json-response 500 {:error (ex-message t)}))))))

(defn- advertise-host
  "Host shown in URLs / UI. Bind may be 0.0.0.0 in Docker; browsers need
   localhost (or LATERALUS_WORKBENCH_PUBLIC_HOST)."
  [bind-host]
  (or (not-empty (System/getenv "LATERALUS_WORKBENCH_PUBLIC_HOST"))
      (when (#{"0.0.0.0" "::" "[::]"} (str bind-host)) "localhost")
      bind-host))

(defn public-url
  "Build a browser-reachable URL for a bind host/port."
  [bind-host port]
  (str "http://" (advertise-host bind-host) ":" port))

(defn start-server!
  "Start http-kit on host/port. Returns {:server :url :port :host}.
   `:url` uses the public/advertise host (not 0.0.0.0)."
  [{:keys [host port handler]
    :or   {host "127.0.0.1" port 0}}]
  (let [run-server (requiring-resolve 'org.httpkit.server/run-server)
        ;; max-ws matches Portal's launcher so large RPC payloads survive
        ;; when Portal is mounted on this server.
        stop!      (run-server handler {:ip host
                                        :port port
                                        :max-body (* 1024 1024 1024)
                                        :max-ws   (* 1024 1024 1024)})
        local-port (or (when (fn? stop!)
                         (:local-port (meta stop!)))
                       port)
        port*      (if (pos? (long (or local-port 0)))
                     local-port
                     port)]
    {:server stop!
     :host   host
     :port   port*
     :url    (public-url host port*)}))

(defn stop-server!
  [server]
  (when server
    (try
      (server :timeout 100)
      (catch Throwable _
        (try (server) (catch Throwable _))))))
