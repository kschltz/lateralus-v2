# MCP client tools (`tools.mcp`)

Lateralus can act as an **MCP client** and attach widely available stdio MCP
servers (the same `command` / `args` / `env` shape used by Claude Desktop /
Cursor). Discovered tools are adapted into the normal `:lateralus/tool-registry`
and invoked through the existing tool loop.

v1 scope: **stdio transport**, **tools/list + tools/call** only. Resources,
prompts, and HTTP/SSE/Streamable-HTTP are follow-ups.

## Design constraints

- **Air-gapped default.** `:lateralus/mcp-tools {:servers {}}` spawns nothing.
- **Protocol + Malli.** Process/JSON-RPC I/O goes through `McpTransport` /
  `McpClient`; impl functions are Malli-instrumented.
- **Integrant-only registration.** No `add-mcp-tool!`.
- **Portable tool names.** MCP names are remapped (`-` → `_`) and **always
  prefixed** with the sanitized server id (`filesystem_read_file`).
- **JVM-only for live servers.** Native-image keeps empty servers; enabling
  servers under native raises a typed error.
- **Untrusted results.** Tool output is size-capped and scanned for injection /
  self-activation markers (same marker vocabulary as `tools.web`).

## Configuration

```clojure
{:lateralus/mcp-tools
 {:enabled? true
  :servers
  {"filesystem"
   {:command "npx"
    :args ["-y" "@modelcontextprotocol/server-filesystem" "/tmp/mcp-sandbox"]
    :env {}
    ;; optional:
    :cwd nil
    :tool-name-prefix nil   ;; default "<server-id>_"; "" disables prefix
    :startup-timeout-ms 30000
    :request-timeout-ms 30000
    :max-result-bytes 65536}}}

 :lateralus/tool-registry [... #ig/ref :lateralus/mcp-tools]}
```

`ig/init` validates config, spawns each server, runs the MCP handshake
(`initialize` → `notifications/initialized` → `tools/list`), and builds Tool
records. If any server fails, init fails (no silent half-registry).
`ig/halt!` closes stdin and SIGTERM/SIGKILL-reaps children.

Demo configs:

| File | Role |
|------|------|
| `resources/lateralus/config.edn` | empty `:servers` (default) |
| `resources/lateralus/native.edn` | empty `:servers` |
| `resources/lateralus/demo-mcp.edn` | fake stdio server via `clojure -M:dev -m fake-mcp-server` |

```bash
clojure -M:dev:run --config resources/lateralus/demo-mcp.edn "use fake_echo"
```

## Security

Configuring an MCP server is equivalent to giving the agent whatever that
server can do (filesystem, network, credentials in `:env`). Prefer a **sandbox
directory**, never `$HOME`, in committed demos. Keep secrets out of EDN.

## Tests

Fast suite (loopback fake server, no subprocess):

```bash
clojure -M:test -n kschltz.agent.tools.mcp.schemas-test
clojure -M:test -n kschltz.agent.tools.mcp.tools-test
```

Offline e2e (real stdio subprocess of `fake-mcp-server`):

```bash
LATERALUS_E2E_FAKE=true clojure -M:e2e -n kschltz.agent.tools.mcp.mcp-e2e-test
```

Live e2e (opt-in, needs `npx` + network):

```bash
LATERALUS_E2E_MCP=live clojure -M:e2e -n kschltz.agent.tools.mcp.mcp-e2e-test
```

Live tests use `@modelcontextprotocol/server-filesystem` against a temp
sandbox and skip cleanly when `npx` is missing.

## Layout

```
src/kschltz/agent/tools/mcp/
  schemas.clj protocol.clj transport.clj client.clj
  names.clj json_schema.clj guards.clj adapt.clj tools.clj
dev/fake_mcp_server.clj
docs/mcp.md
```
