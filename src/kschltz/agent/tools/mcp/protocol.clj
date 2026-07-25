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

(defn close!
  "Close an MCP client (preferred public entry)."
  [client]
  (-close-client! client))

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
