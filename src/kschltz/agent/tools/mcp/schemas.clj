(ns kschltz.agent.tools.mcp.schemas
  "Malli schemas for the lateralus MCP client tool suite.

   Families:
   1. **Config** — `McpToolsConfig` / per-server stanzas (Claude Desktop-like).
   2. **Protocol shapes** — tool descriptors, call results, error envelopes.
   3. **Op I/O** — adapted tools return `:string` JSON (same as web/runtime)."
  (:require [malli.core :as m]))

(def PosInt
  [:int {:min 1}])

(def ServerConfig
  "One MCP server stanza. Mirrors Claude Desktop `mcpServers` entries
   (`command` / `args` / `env`) plus Lateralus timeouts and naming knobs."
  [:map
   [:command :string]
   [:args {:optional true} [:vector :string]]
   [:env {:optional true} [:map-of :string :string]]
   [:cwd {:optional true} [:maybe :string]]
   [:tool-name-prefix {:optional true} [:maybe :string]]
   [:startup-timeout-ms {:optional true} PosInt]
   [:request-timeout-ms {:optional true} PosInt]
   [:max-result-bytes {:optional true} PosInt]])

(def ServerId
  "Server ids may be strings or keywords in EDN."
  [:or [:string {:min 1}] :keyword])

(def McpToolsConfig
  "Integrant config for `:lateralus/mcp-tools`. Default is air-gapped:
   empty `:servers` (or `:enabled? false`) spawns nothing."
  [:map
   [:enabled? {:optional true} :boolean]
   [:servers {:optional true} [:map-of ServerId ServerConfig]]
   ;; Test seam: inject pre-built clients keyed by server id.
   [:clients {:optional true} [:map-of ServerId :any]]
   ;; Test / native seam: when true, refuse non-empty servers.
   [:native-image? {:optional true} :boolean]])

(def JsonRpcId
  [:or :int :string])

(def ToolDescriptor
  "One entry from MCP `tools/list`."
  [:map
   [:name :string]
   [:description {:optional true} [:maybe :string]]
   [:inputSchema {:optional true} :any]])

(def CallToolResult
  "Normalized `tools/call` result after client decoding."
  [:map
   [:content {:optional true} [:vector :any]]
   [:isError {:optional true} :boolean]
   [:structuredContent {:optional true} :any]])

(def ErrorEnvelope
  "Model-visible JSON envelope keys (before serialization)."
  [:map
   [:error :string]
   [:phase :string]
   [:server {:optional true} :string]
   [:tool {:optional true} :string]
   [:truncated? {:optional true} :boolean]
   [:blocked? {:optional true} :boolean]])

(def OutputString
  "Adapted MCP tools JSON-serialize their envelope and return a string."
  :string)

(def OpenArgs
  "Permissive fallback input schema when a tool has no usable inputSchema."
  [:map])

(defn valid-config?
  "True when `config` conforms to `McpToolsConfig`."
  [config]
  (m/validate McpToolsConfig (or config {})))

(defn explain-config
  [config]
  (m/explain McpToolsConfig (or config {})))
