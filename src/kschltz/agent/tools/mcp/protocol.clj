(ns kschltz.agent.tools.mcp.protocol
  "McpClient / McpTransport protocols for the lateralus MCP client.

   All process and JSON-RPC I/O goes through these protocols. Implementations
   raise `ex-info` with `:phase` in
   `#{:disabled :spawn :handshake :protocol :timeout :tool :size-cap :closed}`.
   `-server-info` MUST NOT raise."
  (:require [malli.core :as m]))

(defprotocol McpTransport
  "Byte-stream / process boundary for one MCP server connection."
  (-send! [transport message]
    "Write one JSON-RPC message map (newline-delimited JSON).")
  (-recv! [transport timeout-ms]
    "Blocking read of one JSON-RPC message map. Raises `:phase :timeout`
     when no message arrives within `timeout-ms`. Raises `:phase :closed`
     when the stream ends.")
  (-close-transport! [transport]
    "Close the transport. Idempotent.")
  (-alive? [transport]
    "True when the underlying process/connection is still usable."))

(defprotocol McpClient
  "Session/API boundary for one MCP server."
  (-initialize! [client]
    "Run initialize + notifications/initialized. Returns server result map.")
  (-list-tools [client]
    "Return a vector of tool descriptor maps (`:name`, `:description`,
     `:inputSchema`).")
  (-call-tool [client tool-name arguments]
    "Call `tools/call` with MCP tool name and arguments map. Returns
     normalized `{:content ... :isError bool}`.")
  (-close-client! [client]
    "Graceful session + transport shutdown. Idempotent.")
  (-server-info [client]
    "Negotiated serverInfo / capabilities map. MUST NOT raise."))

(defprotocol McpSession
  "Process/session boundary for the set of live MCP servers.

   Integrant owns one `McpSession` per system. Control tools propose
   closed transitions (like `set_llm_config`); the transitions apply
   interceptor calls mutating methods to reconcile live clients, while
   `transitions.clj` records durable `:mcp/servers` intent. `-registry`
   is read on every tools-plugin seed so ReAct follow-ups see upserts
   same-exchange."
  (-upsert-server! [session server-id server-cfg opts]
    "Connect (or replace) one server, discover tools, adapt them.
     `opts` may include `:reserved-names` (set of non-MCP tool names
     that must not collide). Returns a status map. Raises `ex-info`
     with `:phase` on failure; leaves prior servers intact.")
  (-remove-server! [session server-id]
    "Close and drop one server. Idempotent when unknown. Returns status.")
  (-refresh-server! [session server-id]
    "Re-run `tools/list` for an existing server and rebuild its tools.
     Raises when the server id is unknown.")
  (-registry [session]
    "Current name→Tool map for all connected servers.")
  (-status [session]
    "Serializable inventory (server ids, tool names, dynamic policy).")
  (-dynamic-enabled? [session]
    "True when agent-driven upsert/remove is allowed.")
  (-halt-session! [session]
    "Close every client. Idempotent."))

(defn close!
  "Close an MCP client (preferred public entry)."
  [client]
  (-close-client! client))

(defn halt-session!
  "Halt an MCP session (preferred public entry)."
  [session]
  (-halt-session! session))

(defn mcp-session?
  "True when `x` satisfies `McpSession`."
  [x]
  (satisfies? McpSession x))

(def ServerInfo
  [:map
   [:name {:optional true} :string]
   [:version {:optional true} :string]
   [:protocolVersion {:optional true} :string]
   [:capabilities {:optional true} :any]])

(defn client?
  "True when `x` satisfies `McpClient`."
  [x]
  (satisfies? McpClient x))

(defn transport?
  "True when `x` satisfies `McpTransport`."
  [x]
  (satisfies? McpTransport x))

(defn valid-server-info?
  [m]
  (m/validate ServerInfo (or m {})))
