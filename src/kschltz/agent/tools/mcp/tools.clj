(ns kschltz.agent.tools.mcp.tools
  "Build a Lateralus tool registry from `:lateralus/mcp-tools` config.

   Starts configured stdio MCP servers, discovers tools, adapts them,
   and returns a name→Tool map. Clients are stored in metadata under
   `:mcp/clients` so Integrant `halt-key!` can reap child processes.

   Fail-fast: if any configured server fails handshake/list, the whole
   registry build throws (no silent half-registry)."
  (:require [kschltz.agent.tools.mcp.adapt :as adapt]
            [kschltz.agent.tools.mcp.client :as client]
            [kschltz.agent.tools.mcp.names :as names]
            [kschltz.agent.tools.mcp.protocol :as proto]
            [kschltz.agent.tools.mcp.schemas :as schemas]
            [malli.core :as m]
            [malli.instrument :as mi]))

(defn- native-image?
  "True when running inside a GraalVM native image, or when the config
   forces the native path via `:native-image? true`."
  [config]
  (or (true? (:native-image? config))
      (some? (System/getProperty "org.graalvm.nativeimage.imagecode"))))

(defn- enabled?
  [config]
  (not (false? (:enabled? config))))

(defn- raise
  [phase msg data]
  (throw (ex-info msg (merge {:phase phase} data))))

(defn- list-and-adapt
  [client server-id server-cfg claimed]
  (let [descs (proto/-list-tools client)
        resolved (names/resolve-tool-names
                  server-id
                  (:tool-name-prefix server-cfg)
                  descs
                  claimed)
        registry (adapt/adapt-tools
                  client
                  resolved
                  {:max-result-bytes (or (:max-result-bytes server-cfg)
                                         (* 64 1024))
                   :server-id server-id})]
    {:client client
     :registry registry
     :server-id server-id
     :names (set (keys registry))}))

(defn- start-stdio-server!
  [server-id server-cfg]
  (let [client (client/connect-stdio!
                (assoc server-cfg :server-id server-id))]
    (try
      (list-and-adapt client server-id server-cfg #{})
      (catch Throwable t
        (try (proto/-close-client! client) (catch Throwable _))
        (throw t)))))

(defn- start-injected-client!
  [server-id client server-cfg]
  (when-not (true? (:initialized? server-cfg))
    (when-not (:initialized? (proto/-server-info client))
      (proto/-initialize! client)))
  (list-and-adapt client server-id server-cfg #{}))

(defn mcp-registry
  "Build the MCP tool registry from config.

   Returns a plain map (name→Tool) with metadata:
     `:mcp/clients` — vector of live clients
     `:mcp/server-ids` — vector of server ids started

   Empty/disabled config returns `{}` with no clients.

   Test seam: `:clients {\"id\" client}` injects pre-built clients.
   Pair with `:servers` for prefixes/timeouts; set `:initialized? true`
   on the server cfg (or initialize the client first) to skip initialize."
  [config]
  (let [config (or config {})]
    (when-not (schemas/valid-config? config)
      (raise :protocol
             "Invalid :lateralus/mcp-tools config"
             {:problems (:errors (schemas/explain-config config))}))
    (cond
      (not (enabled? config))
      (with-meta {} {:mcp/clients [] :mcp/server-ids []})

      (and (native-image? config)
           (seq (:servers config)))
      (raise :disabled
             "MCP servers are JVM-only and cannot be enabled under native-image"
             {:servers (vec (keys (:servers config)))})

      (and (empty? (:servers config))
           (empty? (:clients config)))
      (with-meta {} {:mcp/clients [] :mcp/server-ids []})

      :else
      (let [claimed (atom #{})
            started (atom [])
            server-entries
            (if (seq (:clients config))
              (map (fn [[server-id c]]
                     [server-id
                      (merge (or (get-in config [:servers (str server-id)])
                                 (get-in config [:servers server-id])
                                 {})
                             {:__client c})])
                   (:clients config))
              (seq (:servers config)))]
        (try
          (let [pieces
                (mapv
                 (fn [[server-id server-cfg]]
                   (let [sid (str server-id)
                         piece
                         (if-let [c (:__client server-cfg)]
                           (start-injected-client! sid c server-cfg)
                           (start-stdio-server! sid server-cfg))
                         overlap (filter @claimed (:names piece))]
                     (when (seq overlap)
                       (raise :protocol
                              (str "MCP tool name collision across servers: "
                                   (pr-str (vec overlap)))
                              {:server sid :overlap (vec overlap)}))
                     (swap! claimed into (:names piece))
                     (swap! started conj (:client piece))
                     piece))
                 (sort-by (comp str first) server-entries))
                registry (apply merge {} (map :registry pieces))]
            (with-meta registry {:mcp/clients (mapv :client pieces)
                                 :mcp/server-ids (mapv :server-id pieces)}))
          (catch Throwable t
            (doseq [c @started]
              (try (proto/-close-client! c) (catch Throwable _)))
            (throw t)))))))

(defn halt-registry!
  "Close every client stored on registry metadata. Idempotent."
  [registry]
  (doseq [c (:mcp/clients (meta registry))]
    (try (proto/-close-client! c) (catch Throwable _)))
  nil)

(m/=> mcp-registry
      [:=> [:cat [:maybe :map]] :any])

(mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.tools.mcp.tools)]})
