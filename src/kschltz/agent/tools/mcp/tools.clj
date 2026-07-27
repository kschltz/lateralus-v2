(ns kschltz.agent.tools.mcp.tools
  "Build a Lateralus tool registry / session from `:lateralus/mcp-tools`.

   Prefer `kschltz.agent.tools.mcp.session/mcp-session` for Integrant.
   `mcp-registry` remains the convenience entry used by tests and demos:
   it returns a name→Tool map with `:mcp/session` in metadata."
  (:require [kschltz.agent.tools.mcp.session :as session]
            [malli.core :as m]
            [malli.instrument :as mi]))

(defn mcp-session
  "Build an `McpSession` from config. See `session/mcp-session`."
  [config]
  (session/mcp-session config))

(defn mcp-registry
  "Build the MCP tool registry from config.

   Returns a plain map (name→Tool) with metadata:
     `:mcp/session` — owning session
     `:mcp/clients` — vector of live clients
     `:mcp/server-ids` — vector of server ids started

   Empty/disabled config returns `{}` with no clients.

   Test seam: `:clients {\"id\" client}` injects pre-built clients."
  [config]
  (session/mcp-registry config))

(defn halt-registry!
  "Close every client stored on registry metadata. Idempotent."
  [registry]
  (session/halt-registry! registry))

(m/=> mcp-session [:=> [:cat [:maybe :map]] :any])
(m/=> mcp-registry [:=> [:cat [:maybe :map]] :any])

(mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.tools.mcp.tools)]})
