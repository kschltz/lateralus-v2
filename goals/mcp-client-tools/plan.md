# Plan — MCP Client Tools (stdio)

## Solution approach

Mirror the **web-tools / runtime-tools** capability pattern:

```
EDN config  →  Integrant key  →  protocol + Malli  →  Tool registry  →  tools-plugin
```

v1 focuses on **stdio MCP servers** — the transport used by nearly every
widely published server (`npx @modelcontextprotocol/server-*`, `uvx`,
local node/python scripts). Config keys intentionally match the Claude
Desktop `mcpServers` stanza so operators can copy from public docs.

No new interceptor slots. No ad-hoc `add-mcp-tool!`. Optional thin
`mcp-plugin` is deferred until resources/prompts need `:enrich`.

```
┌─────────────────────────────┐
│ :lateralus/mcp-tools (EDN)  │  {:servers {"fs" {:command "npx" ...}}}
└──────────────┬──────────────┘
               ▼
┌─────────────────────────────┐
│ ig/init → spawn + handshake │  initialize → initialized → tools/list
└──────────────┬──────────────┘
               ▼
┌─────────────────────────────┐
│ McpClient (protocol)        │  -list-tools / -call-tool / -close!
│  └─ StdioTransport          │  JSON-RPC over stdin/stdout
└──────────────┬──────────────┘
               ▼
┌─────────────────────────────┐
│ adapt → Tool registry map   │  name remap, JSON Schema → Malli/args
│ merge into tool-registry    │
└─────────────────────────────┘
```

---

## Locked decisions

| Decision | Choice |
|----------|--------|
| Direction | MCP **client** only |
| v1 transport | **stdio** (`command` / `args` / `env`) |
| Discovery | Snapshot at `ig/init` (not per-exchange re-list) |
| Wiring | `:lateralus/mcp-tools` → `:lateralus/tool-registry` |
| Default | `{:servers {}}` — no spawn |
| Tool names | Remap `-`→`_`; prefix with sanitized server id on clash / non-portable names |
| Protocol version | Request `2024-11-05` (negotiate; disconnect on unsupported) |
| Native-image | JVM-only; `native.edn` keeps empty servers; guarded require |
| Resources / prompts | Out of v1 |
| Deps | Prefer thin in-repo JSON-RPC + process client over a heavy Java SDK unless a clear gap appears during Step 2 |

---

## Config schema (target)

```clojure
:lateralus/mcp-tools
{:enabled? true          ;; master switch; false → empty registry, no spawn
 :servers
 {"filesystem"
  {:command "npx"
   :args ["-y" "@modelcontextprotocol/server-filesystem" "/tmp/mcp-sandbox"]
   :env {}
   ;; optional:
   :cwd nil
   :tool-name-prefix nil ;; default: "<server-id>_" with -→_
   :startup-timeout-ms 30000
   :request-timeout-ms 30000
   :max-result-bytes 65536}}

 :lateralus/tool-registry [... #ig/ref :lateralus/mcp-tools]
```

Malli `McpToolsConfig` (approx):

```clojure
[:map
 [:enabled? {:optional true} :boolean]
 [:servers {:optional true}
  [:map-of
   [:string {:min 1}]
   [:map
    [:command :string]
    [:args {:optional true} [:vector :string]]
    [:env {:optional true} [:map-of :string :string]]
    [:cwd {:optional true} [:maybe :string]]
    [:tool-name-prefix {:optional true} [:maybe :string]]
    [:startup-timeout-ms {:optional true} pos-int?]
    [:request-timeout-ms {:optional true} pos-int?]
    [:max-result-bytes {:optional true} pos-int?]]]]]
```

`ig/assert-key` validates before spawn. Invalid config fails fast at init.

---

## Protocol surface

### `McpTransport` (`tools/mcp/transport.clj`)

Process/byte-stream boundary only:

| Method | Meaning |
|--------|---------|
| `-send!` | Write one JSON-RPC message (newline-delimited) |
| `-recv!` | Blocking read one message with timeout |
| `-close!` | Close stdin, wait, SIGTERM, then SIGKILL |
| `-alive?` | Child process still running? |

### `McpClient` (`tools/mcp/protocol.clj`)

Session/API boundary:

| Method | Meaning |
|--------|---------|
| `-initialize!` | `initialize` + `notifications/initialized` |
| `-list-tools` | `tools/list` → vector of tool descriptors |
| `-call-tool` | `tools/call` `{name, arguments}` → content envelope |
| `-close!` | Graceful session + transport shutdown |
| `-server-info` | Negotiated serverInfo / capabilities (never raises) |

Error contract: raise `ex-info` with `:phase` in
`#{:disabled :spawn :handshake :protocol :timeout :tool :size-cap :closed}`.
Tool layer catches and emits JSON error envelopes (same spirit as web).

All public impl fns use `m/=>` + `mi/instrument!` at ns load.

---

## File layout

```
src/kschltz/agent/tools/mcp/
  schemas.clj      ; McpToolsConfig, tool descriptors, call I/O, envelopes
  protocol.clj     ; McpClient
  transport.clj    ; McpTransport + stdio impl
  client.clj       ; JSON-RPC session over transport
  names.clj        ; portable name remap / clash resolution
  adapt.clj        ; MCP tool descriptor → Tool record
  tools.clj        ; mcp-registry builder (config → name→Tool map)
  guards.clj       ; result size caps, control-char strip, injection markers

test/kschltz/agent/tools/mcp/
  schemas_test.clj
  names_test.clj
  guards_test.clj
  protocol_test.clj
  client_test.clj          ; against fake stdio server
  adapt_test.clj
  tools_test.clj
  system_test.clj          ; Integrant init/halt
  mcp_e2e_test.clj         ; ^:e2e offline fake + optional live

dev/
  fake_mcp_server.clj      ; stdio JSON-RPC fake for tests/demos

resources/lateralus/
  demo-mcp.edn             ; example wiring (sandbox filesystem or fake)
  config.edn               ; :lateralus/mcp-tools {:servers {}}
  native.edn               ; empty servers (no spawn)

docs/mcp.md                ; operator + contributor doc
```

Also touch: `system.clj` (assert/init/halt), `cli/profile/templates.clj`
(optional `:mcp` tool-group), `docs/architecture.md`, `README.md`,
`AGENT_INSTRUCTIONS.md`.

---

## Ordered steps

### Step 1 — Schemas + name remap + guards (no process I/O)

**Touches:** `schemas.clj`, `names.clj`, `guards.clj` + unit tests

**Work:**
- Define `McpToolsConfig`, tool descriptor schema, call request/response
  envelopes, error envelope shape.
- `sanitize-tool-name` / `resolve-tool-names`:
  - `-` → `_`
  - strip illegal chars
  - ensure `portable-tool-name?`
  - on clash or non-portable: prefix with sanitized server id
  - hard-fail init if still unresolvable after prefix
- Guards on **model-bound** content (tool results):
  - `:max-result-bytes` truncation with `truncated? true`
  - strip ASCII control chars (except `\n` `\t`)
  - block common self-activation / tool-call JSON markers (reuse web
    guard patterns where practical; do not invent a second unrelated
    marker list without documenting why)

**Verification:**
```bash
clojure -M:test -n kschltz.agent.tools.mcp.schemas-test
clojure -M:test -n kschltz.agent.tools.mcp.names-test
clojure -M:test -n kschltz.agent.tools.mcp.guards-test
```

**Regression guards (must land here or Step 2 and stay green):**
- Default / empty servers config validates.
- `foo-bar` → `foo_bar`; clash `read` from two servers →
  `filesystem_read` / `github_read` (or configured prefixes).
- Oversized result truncates; injection-marker content is flagged or
  stripped per documented policy.

---

### Step 2 — Transport + client + fake MCP server

**Touches:** `transport.clj`, `protocol.clj`, `client.clj`,
`dev/fake_mcp_server.clj`, client/protocol tests

**Work:**
- Stdio transport: spawn `ProcessBuilder`, merge `env`, optional `cwd`,
  newline-delimited JSON-RPC on stdin/stdout; stderr logged but never
  parsed as protocol.
- Client session: id allocation, request/response correlation, ignore
  or log server notifications that v1 does not handle
  (`notifications/tools/list_changed` → warn once; no live re-list).
- Handshake: `initialize` (protocolVersion `2024-11-05`, clientInfo
  `lateralus-v2`) → wait result → `notifications/initialized`.
- `tools/list`, `tools/call` with per-request timeout.
- `close!`: close stdin → wait → SIGTERM → SIGKILL (bounded waits).
- **Fake server** (`dev/fake_mcp_server.clj`): stdio process that
  implements initialize / tools/list / tools/call for 2–3 deterministic
  tools (e.g. `echo`, `add`, `fail`). Used by unit + offline e2e.
  Launch via `clojure -M:dev -m fake-mcp-server` (or equivalent).

**Verification:**
```bash
clojure -M:test -n kschltz.agent.tools.mcp.client-test
clojure -M:test -n kschltz.agent.tools.mcp.protocol-test
```

**Regression guards:**
- Handshake failure → `:phase :handshake`, no hung threads.
- Request timeout → `:phase :timeout`.
- Child exit mid-call → `:phase :closed` or `:transport`.
- `close!` is idempotent; double-close does not throw.
- Stderr noise from server does not break framing.
- Fake server round-trip: list ≥ 2 tools; `echo` returns args; `fail`
  returns structured tool error (isError / content), not client crash.

---

### Step 3 — Adapt MCP tools → `Tool` + registry builder

**Touches:** `adapt.clj`, `tools.clj` + tests

**Work:**
- For each MCP tool descriptor:
  - map name through `names.clj`
  - description from MCP `description`
  - input: prefer JSON Schema from MCP `inputSchema`; validate args
    before `tools/call` (either convert to Malli or validate with a
    JSON-Schema validator — pick one approach in impl and document it;
    output remains `:string` JSON like web tools)
  - `-invoke` → `-call-tool` → guard → JSON envelope string
- `mcp-registry` / `build-registry`:
  - `:enabled? false` or empty servers → `{}`
  - else start clients, list tools, adapt, merge maps
  - partial failure policy: **fail the whole `ig/init`** if any configured
    server fails handshake/list (explicit; no silent half-registry).
    Document escape hatch only if tests prove need (`:ignore-server-errors?`
    deferred — not in v1 unless required).

**Verification:**
```bash
clojure -M:test -n kschltz.agent.tools.mcp.adapt-test
clojure -M:test -n kschltz.agent.tools.mcp.tools-test
```

**Regression guards:**
- Adapted tool names always satisfy `portable-tool-name?`.
- Model-visible disabled/error envelopes are JSON strings with `:phase`.
- Registry is empty when `:enabled? false` even if `:servers` non-empty.

---

### Step 4 — Integrant wiring + halt + defaults

**Touches:** `system.clj`, `config.edn`, `native.edn`, `demo-mcp.edn`,
`system_test.clj`, profile templates (optional)

**Work:**
- `ig/assert-key` / `ig/init-key` / `ig/halt-key!` for `:lateralus/mcp-tools`.
- Halt closes every live `McpClient` (best-effort; log failures).
- Default JVM config: `{:servers {}}` (or `:enabled? false`).
- `native.edn`: empty / disabled; selecting non-empty servers under native
  raises typed `ex-info` (JVM-only), matching mojeek/ddg pattern.
- Wire `#ig/ref :lateralus/mcp-tools` into `:lateralus/tool-registry`
  vectors in defaults + demo.
- Optional profile tool-group `:mcp` in `cli/profile/templates.clj`.
- Demo config uses either fake server or sandbox filesystem path under
  `/tmp` — never a broad home-directory grant in committed demos.

**Verification:**
```bash
clojure -M:test -n kschltz.agent.tools.mcp.system-test
# halt leaves no orphan child processes (assert in test via fake server pid)
```

**Regression guards:**
- `ig/init` + `ig/halt!` round-trip with empty servers (no spawn).
- `ig/init` + `ig/halt!` with fake server: child gone after halt.
- `ig/assert-key` rejects missing `:command`, non-string env values.
- Default `resources/lateralus/config.edn` still starts offline
  (`demo-stub` / stub LLM path unchanged).

---

### Step 5 — Agent-loop integration tests (fast suite)

**Touches:** loop/runtime tests using stub LLM + fake MCP server

**Work:**
- Start system with stub/`scripted` LLM that issues a tool call to a
  fake-MCP-adapted tool (e.g. `fake_echo`).
- Assert: tool runs through dispatch → compose-tool-results → model sees
  content; second turn can continue.
- Assert: tool name in `:llm/request` tools list is portable.
- Assert: MCP tool result passes through existing
  `:tool-content-caps` if configured.

**Verification:**
```bash
clojure -M:test -n kschltz.agent.tools.mcp.tools-test
# plus any new loop integration ns
clojure -M:test   # full fast suite green (aside from known portal_test failures)
```

**Regression guards:**
- MCP tools do not bypass `max-tool-calls-per-turn` /
  `max-tool-calls-per-exchange`.
- Content caps apply to MCP tool names when listed in `loop-opts`.
- Empty MCP registry does not alter non-MCP tool behavior (baseline
  snapshot test: file/web/runtime tools still present with same names).

---

### Step 6 — Offline e2e (`^:e2e`, deterministic)

**Touches:** `mcp_e2e_test.clj`, fake server, docs

**Work:**
- Tag tests `^:e2e`. Default `clojure -M:test` excludes them.
- **Offline path (CI-friendly):** always runnable under
  `LATERALUS_E2E_FAKE=true clojure -M:e2e` (or dedicated
  `LATERALUS_E2E_MCP=fake`):
  1. Spawn `fake_mcp_server` as configured `:command`/`args`.
  2. Full Integrant system with stub or fake HTTP LLM that requests
     `fake_echo` / `fake_add`.
  3. Assert tool list discovery, successful call, structured failure
     call, clean halt.
- Do **not** require Node/npm for offline e2e.

**Verification:**
```bash
LATERALUS_E2E_FAKE=true clojure -M:e2e -n kschltz.agent.tools.mcp.mcp-e2e-test
# or full:
LATERALUS_E2E_FAKE=true clojure -M:e2e
```

**Acceptance assertions (offline e2e):**
- Discovered tool count ≥ 2.
- Echo call returns exact payload.
- Fail tool returns envelope with error phase/status, exchange continues.
- After `ig/halt!`, fake server process is not alive.

---

### Step 7 — Live e2e (opt-in, real published server)

**Touches:** `mcp_e2e_test.clj`, `docs/mcp.md`

**Work:**
- Behind `LATERALUS_E2E_MCP=live` (and network + `npx` available):
  - Launch `@modelcontextprotocol/server-filesystem` against a temp
    sandbox directory created by the test.
  - Write a known file into the sandbox via test fixture (not via MCP).
  - Discover tools; invoke the server's read/list tool (actual MCP tool
    name remapped) to read that file.
  - Assert content match; halt cleans up process; delete sandbox.
- Skip cleanly when `npx` missing or env unset (like Ollama skip).
- Never grant access outside the temp sandbox in committed tests.

**Verification:**
```bash
LATERALUS_E2E_MCP=live clojure -M:e2e -n kschltz.agent.tools.mcp.mcp-e2e-test
```

**Acceptance assertions (live e2e):**
- Handshake succeeds against real filesystem server.
- Remapped list/read tool returns fixture file contents.
- Halt leaves no orphan `npx`/node MCP child.

---

### Step 8 — Docs + quality gate

**Touches:** `docs/mcp.md`, `docs/architecture.md`, `README.md`,
`AGENT_INSTRUCTIONS.md`, component graph in architecture doc

**Work:**
- New `docs/mcp.md`: config reference, security notes (MCP tools are
  arbitrary code / network as implemented by the server), name remap,
  halt lifecycle, how to run fake vs live e2e.
- Update architecture component graph with `:lateralus/mcp-tools`.
- Point AGENT_INSTRUCTIONS canonical sources at `docs/mcp.md` and this
  goals package.
- Add verify greps / commands for MCP defaults (empty servers in
  `config.edn` / `native.edn`).

**Verification:**
```bash
clj-kondo --lint src test
clojure -M:test
LATERALUS_E2E_FAKE=true clojure -M:e2e
rg ':lateralus/mcp-tools' resources/lateralus/config.edn
rg 'add-.*-tool!' src/   # still no matches
```

---

## Regression guard matrix

These must remain true after every step that could break them. Prefer
executable tests over prose-only checks.

| ID | Guard | Suite | How |
|----|-------|-------|-----|
| G1 | Air-gap default: empty/disabled servers spawn nothing | fast | system_test init empty → no Process |
| G2 | Portable tool names only in registry / LLM tools list | fast | names_test + loop integration |
| G3 | Name clash across servers resolved by prefix | fast | names_test |
| G4 | Handshake/spawn failure fails init (no silent half-registry) | fast | system_test / client_test |
| G5 | `halt!` kills children (idempotent) | fast + e2e | pid alive? false after halt |
| G6 | Request timeout → `:phase :timeout` envelope | fast | client_test |
| G7 | Result size cap / truncation | fast | guards_test |
| G8 | Injection / self-activation markers handled on results | fast | guards_test |
| G9 | MCP tools respect loop tool-call caps | fast | loop integration |
| G10 | Non-MCP registries unchanged when MCP empty | fast | system/registry snapshot |
| G11 | Offline e2e: fake server list+call+fail+halt | e2e fake | mcp_e2e_test |
| G12 | Live e2e: real filesystem server read in sandbox | e2e live | mcp_e2e_test env-gated |
| G13 | `native.edn` does not enable MCP servers | fast / lint | config assert or rg gate |
| G14 | No `add-*-tool!`; Integrant-only registration | lint | existing rg gate |
| G15 | Protocol impl fns Malli-instrumented | fast | protocol_test / meta assert |

---

## E2E test plan (detail)

### A. Offline / fake (`LATERALUS_E2E_FAKE=true` or always under `-M:e2e` when fake server is local)

| Test | Assert |
|------|--------|
| `e2e-mcp-discovers-fake-tools` | init → registry contains remapped fake tools |
| `e2e-mcp-echo-round-trip` | stub/fake LLM tool-call → echo content in next compose |
| `e2e-mcp-tool-error-is-model-visible` | fail tool → JSON error, no chain crash |
| `e2e-mcp-halt-reaps-child` | after halt, process dead |

### B. Live (`LATERALUS_E2E_MCP=live`)

| Test | Assert |
|------|--------|
| `e2e-mcp-filesystem-server-read` | temp sandbox + real `@modelcontextprotocol/server-filesystem` read |
| `e2e-mcp-filesystem-halt-reaps-child` | no orphan after halt |

Live tests **auto-skip** when env unset or `npx` unavailable — skip ≠ fail.

### C. What e2e intentionally does not cover in v1

- OAuth / remote Streamable-HTTP servers
- `tools/list_changed` hot reload
- Resources / prompts
- Multi-GB result payloads

---

## Security notes (must appear in `docs/mcp.md`)

- Configuring an MCP server is equivalent to giving the agent whatever
  that server can do (filesystem, network, credentials in `env`).
- Demo configs must use a **sandbox directory**, never `$HOME`.
- Secrets belong in env vars / profile secrets — not committed EDN.
- Treat tool results as untrusted model input (guards G7/G8).
- Default remains off for air-gapped and native builds.

---

## Risk register

| Risk | Mitigation |
|------|------------|
| Framing bugs from noisy stderr / partial reads | Fake server stress + dedicated transport tests; stderr drain thread |
| Hung child processes | Timeouts + halt SIGTERM/SIGKILL ladder + e2e reap asserts |
| JSON Schema ↔ Malli impedance | Validate with one stack; keep output as JSON string |
| Real server package drift | Live e2e opt-in + lenient skip; pin package name in docs |
| Tool name collisions with built-ins (`read_file`) | Always prefix by default **or** prefix on clash — **choose during Step 1 impl: default prefix `<server>_`** for predictability with third-party servers |

**Default naming policy (locked here):** always prefix with sanitized
server id (`filesystem_read_file`), optional `:tool-name-prefix` override
(`""` only if explicitly set and all names portable + unique).

---

## Out of scope follow-ups

1. HTTP / Streamable-HTTP transport for remote MCP hosts
2. Claude Desktop JSON importer (`mcpServers` → EDN)
3. Resources + prompts via `mcp-plugin` `:enrich`
4. Dynamic re-list on `tools/list_changed`
5. Native-image subprocess story

---

## Done when

- [ ] Steps 1–8 merged
- [ ] Fast suite covers G1–G10, G13–G15
- [ ] `LATERALUS_E2E_FAKE=true clojure -M:e2e` covers G11
- [ ] Live G12 documented and skip-safe
- [ ] `docs/mcp.md` + architecture/README/AGENT_INSTRUCTIONS updated
- [ ] Default configs spawn zero MCP processes
