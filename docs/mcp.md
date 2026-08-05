# MCP client tools (`tools.mcp`)

Lateralus can act as an **MCP client** and attach MCP servers as first-class
agent tools. Supported transports:

| Transport | Config | Use |
|-----------|--------|-----|
| **stdio** | `:command` / `:args` / `:env` | Local Claude Desktop / Cursor-style servers |
| **Streamable HTTP** | `:url` (+ optional auth headers) | Remote MCP endpoints |

Discovered tools are adapted into `:lateralus/tool-registry` and invoked
through the normal tool loop. Scope today: **tools/list + tools/call**.
Resources, prompts, and OAuth interactive login are follow-ups.

## Design constraints

- **Air-gapped default.** `:lateralus/mcp-tools {:servers {}}` connects nothing.
- **Protocol + Malli.** Process/HTTP/JSON-RPC I/O goes through `McpTransport` /
  `McpClient` / `McpSession`; impl functions are Malli-instrumented.
- **No `add-mcp-tool!`.** Mid-session changes go through control tools that
  propose closed transitions (`mcp_upsert_server`, …); live connect/close
  runs in the transitions apply stage — same monadic pattern as
  `set_llm_config`. See [`docs/dynamic-mcp-tool-setup.md`](dynamic-mcp-tool-setup.md).
- **Dynamic policy on by default.** JVM configs enable mid-session
  upsert/remove; set `:dynamic {:enabled? false}` to lock. List/refresh
  work for already-connected servers. Native-image configs keep dynamic off.
- **Portable tool names.** MCP names are remapped (`-` → `_`) and **always
  prefixed** with the sanitized server id (`filesystem_read_file`).
- **JVM-only for live servers.** Native-image keeps empty servers.
- **Untrusted results.** Tool output is size-capped and scanned for injection /
  self-activation markers (same marker vocabulary as `tools.web`).
- **Remote URL guards.** HTTPS-only and block private/loopback by default
  (reuse web SSRF checks). Opt in to `:allow-http?` / `:allow-loopback?` for
  local fake servers.

## Configuration

### Stdio (local)

```clojure
{:lateralus/mcp-tools
 {:servers
  {"filesystem"
   {:transport :stdio          ;; optional; inferred from :command
    :command "npx"
    :args ["-y" "@modelcontextprotocol/server-filesystem" "/tmp/mcp-sandbox"]
    :env {}
    :tool-name-prefix nil
    :startup-timeout-ms 30000
    :request-timeout-ms 30000
    :max-result-bytes 65536}}}
 :lateralus/tool-registry [... #ig/ref :lateralus/mcp-tools]}
```

### Streamable HTTP (remote)

```clojure
{:lateralus/mcp-tools
 {:servers
  {"acme"
   {:transport :http           ;; optional; inferred from :url
    :url "https://mcp.example.com/mcp"
    ;; Auth v1 — pick one or combine:
    :bearer-token-env "ACME_MCP_TOKEN"   ;; preferred (no secrets in EDN)
    ;; :bearer-token "…"                 ;; avoid committing
    :headers {"X-Tenant" "prod"}
    :request-timeout-ms 30000
    :max-result-bytes 65536
    ;; Local fake / http://127.0.0.1 only:
    ;; :allow-http? true
    ;; :allow-loopback? true
    }}}}
```

`ig/init` validates config, builds an `McpSession`, connects each boot
server, runs the MCP handshake (`initialize` → `notifications/initialized`
→ `tools/list`), and adapts Tool records onto the session registry.
Control tools live under `:lateralus/mcp-session-tools`; the tools plugin
merges `session.registry` live on every seed. If any boot server fails,
init fails (no silent half-registry). `ig/halt!` closes transports (and
SIGTERM/SIGKILL-reaps stdio children).

### Mid-session (dynamic)

Default JVM configs already enable this:

```clojure
{:lateralus/mcp-tools
 {:servers {}
  :dynamic {:enabled? true}}}
```

The agent can call `mcp_upsert_server` / `mcp_remove_server` /
`mcp_refresh_server` / `mcp_list_servers`. Upserts replace same server-id
after closing the prior client. New tools are visible on the next LLM
call of the same exchange. Set `:dynamic {:enabled? false}` to refuse
upsert/remove.

Ollama Cloud demo (requires `OLLAMA_API_KEY`; starts a local fake HTTP
MCP server, then ADD → use → EDIT → REMOVE mid-session):

```bash
python3 scripts/demo-ollama-cloud-mcp-dynamic-pty.py
# or:
./scripts/demo-ollama-cloud-mcp-dynamic.sh
```

Config: `resources/lateralus/demo-ollama-cloud-mcp-dynamic.edn`.

Demo configs:

| File | Role |
|------|------|
| `resources/lateralus/config.edn` | empty `:servers` (default) |
| `resources/lateralus/native.edn` | empty `:servers` |
| `resources/lateralus/demo-mcp.edn` | fake stdio server |
| `dev/fake_mcp_http_server.clj` | fake Streamable HTTP server for tests/demos |

```bash
# stdio demo session
clojure -M:dev -m demo-mcp-session

# remote Streamable HTTP demo (starts in-process fake Jetty + agent exchange)
clojure -M:dev -m demo-mcp-http-session

# or stand-alone fake HTTP server for manual config:
clojure -M:dev -m fake-mcp-http-server   # prints http://127.0.0.1:<port>/mcp
# then point :url at that URL with :allow-http? true :allow-loopback? true
```

## Security

- Configuring an MCP server grants the agent whatever that server can do.
- Prefer sandbox directories for filesystem servers; never `$HOME` in demos.
- Keep secrets in env vars (`:bearer-token-env`), not committed EDN.
- Remote URLs are SSRF-checked; do not set `:allow-loopback?` / `:allow-http?`
  in production configs.

## Auth (current vs deferred)

| Mode | Status |
|------|--------|
| Static Bearer / custom headers | **Supported** |
| OAuth 2.1 + discovery + refresh | Deferred |
| Dynamic client registration / browser login | Deferred |

## Tests

```bash
# Fast suite (includes HTTP fake Jetty + SSRF guards)
clojure -M:test -n kschltz.agent.tools.mcp.http-test
clojure -M:test -n kschltz.agent.tools.mcp.url-test
clojure -M:test -n kschltz.agent.tools.mcp.schemas-test

# Offline e2e: stdio subprocess + in-process HTTP fake
LATERALUS_E2E_FAKE=true clojure -M:e2e -n kschltz.agent.tools.mcp.mcp-e2e-test

# Live stdio filesystem server (opt-in)
LATERALUS_E2E_MCP=live clojure -M:e2e -n kschltz.agent.tools.mcp.mcp-e2e-test
```

## Layout

```
src/kschltz/agent/tools/mcp/
  schemas.clj protocol.clj transport.clj http.clj url.clj
  client.clj names.clj json_schema.clj guards.clj adapt.clj tools.clj
dev/fake_mcp_server.clj
dev/fake_mcp_http_server.clj
dev/demo_mcp_session.clj
docs/mcp.md
```
