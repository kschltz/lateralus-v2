(ns kschltz.agent.tools.mcp.session
  "Integrant-owned live MCP session.

   Owns connected clients and the derived name→Tool registry. Boot seed
   comes from `:lateralus/mcp-tools` config; control tools may upsert /
   remove / refresh when dynamic policy is enabled (upsert/remove only;
   refresh/list always allowed for connected servers).

   Network/process I/O stays behind `McpClient` / `McpTransport`. This
   namespace is Malli-instrumented on its public constructors."
  (:require [kschltz.agent.tools.mcp.adapt :as adapt]
            [kschltz.agent.tools.mcp.client :as client]
            [kschltz.agent.tools.mcp.names :as names]
            [kschltz.agent.tools.mcp.protocol :as proto]
            [kschltz.agent.tools.mcp.schemas :as schemas]
            [malli.core :as m]
            [malli.instrument :as mi]))

(defn- raise
  [phase msg data]
  (throw (ex-info msg (merge {:phase phase} data))))

(defn- native-image?
  [config]
  (or (true? (:native-image? config))
      (some? (System/getProperty "org.graalvm.nativeimage.imagecode"))))

(defn- enabled?
  [config]
  (not (false? (:enabled? config))))

(defn- dynamic-enabled?
  [config]
  (true? (get-in config [:dynamic :enabled?])))

(defn redact-server-config
  "Model/log-safe copy of a server stanza (no bearer token / raw secrets)."
  [cfg]
  (let [cfg (or cfg {})]
    (cond-> (dissoc cfg :bearer-token :http-fn :__client :env :initialized?)
      (contains? cfg :bearer-token) (assoc :bearer-token-set true)
      (contains? cfg :env) (assoc :env-keys (vec (sort (map str (keys (:env cfg)))))
                                  :env-set true))))

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
     :config server-cfg
     :names (set (keys registry))}))

(defn- connect-server!
  [server-id server-cfg]
  (if-let [c (:__client server-cfg)]
    (do
      (when-not (true? (:initialized? server-cfg))
        (when-not (:initialized? (proto/-server-info c))
          (proto/-initialize! c)))
      (list-and-adapt c server-id server-cfg #{}))
    (let [client (client/connect! (assoc server-cfg :server-id server-id))]
      (try
        (list-and-adapt client server-id server-cfg #{})
        (catch Throwable t
          (try (proto/-close-client! client) (catch Throwable _))
          (throw t))))))

(defn- close-entry!
  [entry]
  (when-let [c (:client entry)]
    (try (proto/-close-client! c) (catch Throwable _))))

(defn- rebuild-registry
  [entries]
  (apply merge {} (map :registry (vals entries))))

(defn- claimed-names
  "Tool names claimed by other servers plus optional reserved static names."
  [entries except-id reserved]
  (into (or reserved #{})
        (mapcat :names)
        (vals (dissoc entries (str except-id)))))

(defn- entry-status
  [entry]
  {:server-id (:server-id entry)
   :tools (vec (sort (:names entry)))
   :tool-count (count (:names entry))
   :config (redact-server-config (:config entry))})

(defn- refresh-entry
  "Re-list tools on an existing client; closes nothing on failure."
  [entry claimed]
  (list-and-adapt (:client entry) (:server-id entry) (:config entry) claimed))

(defrecord AtomMcpSession [state]
  proto/McpSession
  (-upsert-server! [_ server-id server-cfg opts]
    (let [sid (str server-id)
          reserved (or (:reserved-names opts) #{})
          snap @state]
      (when (native-image? (:config snap))
        (raise :disabled
               "MCP servers are JVM-only and cannot be enabled under native-image"
               {:server sid}))
      (when-not (schemas/valid-server-config? server-cfg)
        (raise :protocol
               "Invalid MCP server config"
               {:server sid
                :problems (:errors (schemas/explain-server-config server-cfg))}))
      (let [old (get-in snap [:entries sid])
            ;; Strip test-only inject keys from durable config copy later.
            clean-cfg (dissoc server-cfg :__client)
            piece (connect-server! sid server-cfg)
            piece (assoc piece :config clean-cfg)
            claimed (claimed-names (:entries snap) sid reserved)
            overlap (filter claimed (:names piece))]
        (when (seq overlap)
          (close-entry! piece)
          (raise :protocol
                 (str "MCP tool name collision: " (pr-str (vec overlap)))
                 {:server sid :overlap (vec overlap)}))
        (let [entries (assoc (:entries snap) sid piece)
              next {:entries entries
                    :registry (rebuild-registry entries)
                    :config (:config snap)}]
          (reset! state next)
          (when old (close-entry! old))
          (entry-status piece)))))

  (-remove-server! [_ server-id]
    (let [sid (str server-id)
          removed (atom nil)]
      (swap! state
             (fn [snap]
               (if-let [entry (get-in snap [:entries sid])]
                 (do
                   (reset! removed entry)
                   (let [entries (dissoc (:entries snap) sid)]
                     {:entries entries
                      :registry (rebuild-registry entries)
                      :config (:config snap)}))
                 snap)))
      (when-let [entry @removed]
        (close-entry! entry))
      {:server-id sid
       :removed (boolean @removed)
       :tools (if-let [e @removed] (vec (sort (:names e))) [])}))

  (-refresh-server! [_ server-id]
    (let [sid (str server-id)
          snap @state
          entry (get-in snap [:entries sid])]
      (when-not entry
        (raise :protocol
               (str "Unknown MCP server: " (pr-str sid))
               {:server sid
                :known (vec (sort (keys (:entries snap))))}))
      (let [claimed (claimed-names (:entries snap) sid #{})
            piece (refresh-entry entry claimed)
            piece (assoc piece :config (:config entry))
            entries (assoc (:entries snap) sid piece)
            next {:entries entries
                  :registry (rebuild-registry entries)
                  :config (:config snap)}]
        (reset! state next)
        (entry-status piece))))

  (-registry [_] (or (:registry @state) {}))

  (-status [_]
    (let [snap @state
          cfg (:config snap)]
      {:enabled? (enabled? cfg)
       :dynamic-enabled? (dynamic-enabled? cfg)
       :servers (mapv entry-status
                      (sort-by :server-id (vals (:entries snap))))
       :tool-names (vec (sort (keys (or (:registry snap) {}))))
       :tool-count (count (or (:registry snap) {}))}))

  (-dynamic-enabled? [_] (dynamic-enabled? (:config @state)))

  (-halt-session! [_]
    (let [prev (atom nil)]
      (swap! state
             (fn [snap]
               (reset! prev snap)
               {:entries {}
                :registry {}
                :config (:config snap)}))
      (doseq [e (vals (:entries @prev))]
        (close-entry! e))
      nil)))

(defn- boot-entries!
  "Start every configured server. Fail-fast: on error close already-started."
  [config]
  (let [started (atom [])]
    (try
      (let [server-entries
            (if (seq (:clients config))
              (map (fn [[server-id c]]
                     [server-id
                      (merge (or (get-in config [:servers (str server-id)])
                                 (get-in config [:servers server-id])
                                 {})
                             {:__client c})])
                   (:clients config))
              (seq (:servers config)))
            pieces
            (mapv
             (fn [[server-id server-cfg]]
               (let [sid (str server-id)
                     claimed (into #{} (mapcat :names) @started)
                     piece (let [p (connect-server! sid server-cfg)]
                             (assoc p :config (dissoc server-cfg :__client)))
                     overlap (filter claimed (:names piece))]
                 (when (seq overlap)
                   (close-entry! piece)
                   (raise :protocol
                          (str "MCP tool name collision across servers: "
                               (pr-str (vec overlap)))
                          {:server sid :overlap (vec overlap)}))
                 (swap! started conj piece)
                 piece))
             (sort-by (comp str first) server-entries))]
        (into {} (map (fn [p] [(:server-id p) p])) pieces))
      (catch Throwable t
        (doseq [p @started] (close-entry! p))
        (throw t)))))

(defn mcp-session
  "Build an `McpSession` from `:lateralus/mcp-tools` config.

   Empty/disabled config yields a live session with no clients.
   Non-empty `:servers` (or injected `:clients`) are connected at boot
   (fail-fast, same as the former `mcp-registry`)."
  [config]
  (let [config (or config {})]
    (when-not (schemas/valid-config? config)
      (raise :protocol
             "Invalid :lateralus/mcp-tools config"
             {:problems (:errors (schemas/explain-config config))}))
    (cond
      (not (enabled? config))
      (->AtomMcpSession (atom {:entries {} :registry {} :config config}))

      (and (native-image? config) (seq (:servers config)))
      (raise :disabled
             "MCP servers are JVM-only and cannot be enabled under native-image"
             {:servers (vec (keys (:servers config)))})

      (and (empty? (:servers config)) (empty? (:clients config)))
      (->AtomMcpSession (atom {:entries {} :registry {} :config config}))

      :else
      (let [entries (boot-entries! config)]
        (->AtomMcpSession
         (atom {:entries entries
                :registry (rebuild-registry entries)
                :config config}))))))

(defn mcp-registry
  "Build a name→Tool registry from config (convenience / tests).

   Returns a plain map with metadata:
     `:mcp/session` — the owning `McpSession`
     `:mcp/clients` — vector of live clients (compat with older tests)
     `:mcp/server-ids` — vector of server ids

   Prefer holding the session directly in Integrant."
  [config]
  (let [session (mcp-session config)
        registry (proto/-registry session)
        status (proto/-status session)
        clients (mapv (fn [sid]
                        (:client (get (:entries @(:state session)) sid)))
                      (map :server-id (:servers status)))]
    (with-meta registry
      {:mcp/session session
       :mcp/clients (vec (remove nil? clients))
       :mcp/server-ids (mapv :server-id (:servers status))})))

(defn halt-registry!
  "Close every client owned by a registry built via `mcp-registry`.
   Idempotent. Prefer `proto/halt-session!` when you hold the session."
  [registry]
  (if-let [session (:mcp/session (meta registry))]
    (proto/halt-session! session)
    (doseq [c (:mcp/clients (meta registry))]
      (try (proto/-close-client! c) (catch Throwable _))))
  nil)

(m/=> mcp-session [:=> [:cat [:maybe :map]] :any])
(m/=> mcp-registry [:=> [:cat [:maybe :map]] :any])
(m/=> redact-server-config [:=> [:cat [:maybe :map]] :map])

(mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.tools.mcp.session)]})
