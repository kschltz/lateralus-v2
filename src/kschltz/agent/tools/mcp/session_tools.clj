(ns kschltz.agent.tools.mcp.session-tools
  "Control tools for mid-session MCP setup.

   Mutating tools (`mcp_upsert_server`, `mcp_remove_server`,
   `mcp_refresh_server`) are pure proposers — same pattern as
   `set_llm_config`. They emit allowlisted transition envelopes; live
   connect/close/refresh runs in the transitions apply/reconcile stage
   (never inside `-invoke`). `mcp_list_servers` is read-only via
   `McpSession/-status`."
  (:require [cheshire.core :as json]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.mcp.protocol :as proto]
            [kschltz.agent.tools.mcp.schemas :as schemas]
            [kschltz.agent.tools.mcp.session :as session]
            [kschltz.agent.transitions :as tr]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def UpsertInput
  [:map {:closed true}
   [:server-id [:string {:min 1}]]
   [:config schemas/ServerConfig]])

(def ServerIdInput
  [:map {:closed true}
   [:server-id [:string {:min 1}]]])

(def ListInput
  [:map {:closed true}])

(defn- phase-of
  [^Throwable t]
  (let [p (:phase (ex-data t))]
    (if (keyword? p) (name p) "tool")))

(defn- error-envelope
  [tool-name ^Throwable t]
  (tr/encode-result
   {:ok false
    :tool tool-name
    :error (or (ex-message t) (.getName (class t)))
    :phase (phase-of t)
    :class (.getName (class t))}))

(defn- mcp-servers-view
  "Model-safe view of durable `:mcp/servers` intent."
  [ctx]
  (into {}
        (map (fn [[k v]] [(str k) (session/redact-server-config v)]))
        (or (get-in ctx [:agent/state :mcp/servers]) {})))

(defrecord McpUpsertServerTool [session]
  tool/Tool
  (-name [_] "mcp_upsert_server")
  (-description [_]
    "Propose connecting (or replacing) an MCP server for this session.
     Emits a :mcp-upsert-server transition; connect + tools/list run in
     the apply stage before the next LLM call (including ReAct
     follow-ups). Requires dynamic MCP setup (on by default; lock with
     :dynamic {:enabled? false}). Provide server-id and a stdio
     (:command) or HTTP (:url) config map.")
  (-input-schema [_] UpsertInput)
  (-output-schema [_] :string)
  (-invoke [_ args ctx]
    (try
      (when-not (proto/-dynamic-enabled? session)
        (throw (ex-info
                "Dynamic MCP setup is disabled; set :dynamic {:enabled? true} on :lateralus/mcp-tools"
                {:phase :disabled})))
      (let [sid (:server-id args)
            cfg (schemas/normalize-server-config (:config args))
            before (mcp-servers-view ctx)
            op {:op :mcp-upsert-server :server-id sid :config cfg}
            after (assoc before sid (session/redact-server-config cfg))]
        (tr/encode-result
         {:ok true
          :tool "mcp_upsert_server"
          :pending "same-exchange"
          :server-id sid
          :before before
          :after after
          :transition op}))
      (catch Throwable t
        (error-envelope "mcp_upsert_server" t)))))

(defrecord McpRemoveServerTool [session]
  tool/Tool
  (-name [_] "mcp_remove_server")
  (-description [_]
    "Propose disconnecting an MCP server and dropping its tools.
     Emits a :mcp-remove-server transition; close runs in the apply
     stage. Requires dynamic MCP setup enabled. Idempotent when the
     server id is unknown.")
  (-input-schema [_] ServerIdInput)
  (-output-schema [_] :string)
  (-invoke [_ args ctx]
    (try
      (when-not (proto/-dynamic-enabled? session)
        (throw (ex-info
                "Dynamic MCP setup is disabled; set :dynamic {:enabled? true} on :lateralus/mcp-tools"
                {:phase :disabled})))
      (let [sid (:server-id args)
            before (mcp-servers-view ctx)
            op {:op :mcp-remove-server :server-id sid}
            after (dissoc before sid)]
        (tr/encode-result
         {:ok true
          :tool "mcp_remove_server"
          :pending "same-exchange"
          :server-id sid
          :before before
          :after after
          :transition op}))
      (catch Throwable t
        (error-envelope "mcp_remove_server" t)))))

(defrecord McpRefreshServerTool [session]
  tool/Tool
  (-name [_] "mcp_refresh_server")
  (-description [_]
    "Propose re-running tools/list for a connected MCP server.
     Emits a :mcp-refresh-server transition; refresh runs in the apply
     stage. Does not require dynamic upsert policy.")
  (-input-schema [_] ServerIdInput)
  (-output-schema [_] :string)
  (-invoke [_ args _ctx]
    (try
      (let [sid (:server-id args)
            op {:op :mcp-refresh-server :server-id sid}]
        (tr/encode-result
         {:ok true
          :tool "mcp_refresh_server"
          :pending "same-exchange"
          :server-id sid
          :transition op}))
      (catch Throwable t
        (error-envelope "mcp_refresh_server" t)))))

(defrecord McpListServersTool [session]
  tool/Tool
  (-name [_] "mcp_list_servers")
  (-description [_]
    "List MCP servers connected in this session and their tool names.
     Read-only; does not change session state.")
  (-input-schema [_] ListInput)
  (-output-schema [_] :string)
  (-invoke [_ _args _ctx]
    (try
      (let [st (proto/-status session)]
        (json/generate-string
         {:ok true
          :tool "mcp_list_servers"
          :dynamic-enabled? (:dynamic-enabled? st)
          :servers (:servers st)
          :tool-names (:tool-names st)
          :tool-count (:tool-count st)}
         {:pretty true}))
      (catch Throwable t
        (error-envelope "mcp_list_servers" t)))))

(defn session-tools-registry
  "Return control tools bound to `session`.

   When `session` is nil, returns {}."
  [session]
  (if-not (proto/mcp-session? session)
    {}
    {"mcp_upsert_server"  (->McpUpsertServerTool session)
     "mcp_remove_server"  (->McpRemoveServerTool session)
     "mcp_refresh_server" (->McpRefreshServerTool session)
     "mcp_list_servers"   (->McpListServersTool session)}))

(m/=> session-tools-registry
      [:=> [:cat [:maybe :any]] :map])

(defn instrument!
  []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.tools.mcp.session-tools)]}))

(instrument!)
