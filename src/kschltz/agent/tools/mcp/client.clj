(ns kschltz.agent.tools.mcp.client
  "JSON-RPC MCP session over an `McpTransport`.

   Implements initialize / tools/list / tools/call against protocol
   version `2024-11-05`. Server notifications (no `:id`) are skipped while
   waiting for a matching response. `tools/list_changed` is ignored in v1
   (discovery is snapshotted at init)."
  (:require [kschltz.agent.tools.mcp.protocol :as proto]
            [kschltz.agent.tools.mcp.transport :as transport]
            [malli.core :as m]
            [malli.instrument :as mi])
  (:import [java.util.concurrent.atomic AtomicLong]))

(def protocol-version
  "MCP protocol version requested at initialize."
  "2024-11-05")

(def client-info
  {:name "lateralus-v2"
   :version "0.1.0"})

(defn- raise
  [phase msg data]
  (throw (ex-info msg (merge {:phase phase} data))))

(defn- next-id!
  [^AtomicLong counter]
  (.incrementAndGet counter))

(defn- request!
  "Send a JSON-RPC request and wait for the matching response id."
  [transport id-counter method params timeout-ms]
  (let [id (next-id! id-counter)
        msg (cond-> {:jsonrpc "2.0"
                     :id id
                     :method method}
              (some? params) (assoc :params params))]
    (proto/-send! transport msg)
    (loop [spins 0]
      (when (> spins 64)
        (raise :protocol "Too many MCP notifications before response"
               {:method method :id id}))
      (let [resp (proto/-recv! transport timeout-ms)]
        (cond
          ;; Notification or server request we don't handle in v1.
          (nil? (:id resp))
          (recur (inc spins))

          (not= (:id resp) id)
          (recur (inc spins))

          (some? (:error resp))
          (let [err (:error resp)]
            (raise :protocol
                   (str "MCP error for " method ": "
                        (or (:message err) (pr-str err)))
                   {:method method :id id :error err}))

          :else
          (:result resp))))))

(defn- notify!
  [transport method params]
  (proto/-send! transport
                (cond-> {:jsonrpc "2.0" :method method}
                  (some? params) (assoc :params params))))

(defn make-client
  "Build an `McpClient` over `transport`.

   Options: `:server-id`, `:startup-timeout-ms`, `:request-timeout-ms`."
  [transport {:keys [server-id startup-timeout-ms request-timeout-ms]
              :or {startup-timeout-ms 30000
                   request-timeout-ms 30000}}]
  (let [id-counter (AtomicLong. 0)
        state (atom {:initialized? false
                     :server-info {}
                     :closed? false})]
    (reify proto/McpClient
      (-initialize! [_]
        (when (:closed? @state)
          (raise :closed "MCP client is closed" {:server server-id}))
        (let [result (request! transport id-counter
                               "initialize"
                               {:protocolVersion protocol-version
                                :capabilities {}
                                :clientInfo client-info}
                               startup-timeout-ms)
              pv (:protocolVersion result)]
          (when (and pv (not= pv protocol-version))
            ;; Accept only the version we speak in v1.
            (raise :handshake
                   (str "Unsupported MCP protocol version: " pv)
                   {:server server-id :protocolVersion pv}))
          (notify! transport "notifications/initialized" nil)
          (let [info {:name (get-in result [:serverInfo :name])
                      :version (get-in result [:serverInfo :version])
                      :protocolVersion pv
                      :capabilities (:capabilities result)}]
            (swap! state assoc :initialized? true :server-info info)
            info)))

      (-list-tools [_]
        (when (:closed? @state)
          (raise :closed "MCP client is closed" {:server server-id}))
        (when-not (:initialized? @state)
          (raise :handshake "MCP client not initialized" {:server server-id}))
        (let [result (request! transport id-counter "tools/list" {}
                               request-timeout-ms)
              tools (or (:tools result) [])]
          (mapv (fn [t]
                  (cond-> {:name (:name t)}
                    (contains? t :description) (assoc :description (:description t))
                    (contains? t :inputSchema) (assoc :inputSchema (:inputSchema t))))
                tools)))

      (-call-tool [_ tool-name arguments]
        (when (:closed? @state)
          (raise :closed "MCP client is closed" {:server server-id}))
        (when-not (:initialized? @state)
          (raise :handshake "MCP client not initialized" {:server server-id}))
        (let [result (request! transport id-counter
                               "tools/call"
                               {:name tool-name
                                :arguments (or arguments {})}
                               request-timeout-ms)]
          {:content (or (:content result) [])
           :isError (boolean (:isError result))
           :structuredContent (:structuredContent result)}))

      (-close-client! [_]
        (when-not (:closed? @state)
          (swap! state assoc :closed? true)
          (try (proto/-close-transport! transport) (catch Throwable _))))

      (-server-info [_]
        (try
          (merge (or (:server-info @state) {})
                 {:initialized? (boolean (:initialized? @state))
                  :closed? (boolean (:closed? @state))})
          (catch Throwable _ {:initialized? false}))))))

(defn connect-stdio!
  "Spawn a stdio server and return an initialized `McpClient`.

   `server-cfg` is a `ServerConfig` map plus `:server-id`.
   Raises `:spawn` / `:handshake` on failure; caller should close on error."
  [server-cfg]
  (let [server-id (:server-id server-cfg)
        transport (transport/spawn-stdio!
                   (select-keys server-cfg [:command :args :env :cwd]))
        client (make-client transport
                            {:server-id server-id
                             :startup-timeout-ms
                             (or (:startup-timeout-ms server-cfg) 30000)
                             :request-timeout-ms
                             (or (:request-timeout-ms server-cfg) 30000)})]
    (try
      (proto/-initialize! client)
      client
      (catch Throwable t
        (try (proto/-close-client! client) (catch Throwable _))
        (throw t)))))

(m/=> make-client
      [:=> [:cat :any [:map
                       [:server-id {:optional true} :string]
                       [:startup-timeout-ms {:optional true} :int]
                       [:request-timeout-ms {:optional true} :int]]]
       :any])

(m/=> connect-stdio!
      [:=>
       [:cat
        [:map
         [:command :string]
         [:server-id {:optional true} :string]
         [:args {:optional true} [:vector :string]]
         [:env {:optional true} [:map-of :string :string]]
         [:cwd {:optional true} [:maybe :string]]
         [:startup-timeout-ms {:optional true} :int]
         [:request-timeout-ms {:optional true} :int]]]
       :any])

(mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.tools.mcp.client)]})
