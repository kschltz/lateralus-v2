# Design exploration — Dynamic MCP tool setup

Status: **exploration** (no implementation yet).  
Companion goal: [`goals/dynamic-mcp-tool-setup/goal.md`](../goals/dynamic-mcp-tool-setup/goal.md).

## 1. Problem

Today, runtime config updates work only for **LLM session knobs**:

```
set_llm_config → {:transition {:op :set-llm …}}
  → harvest-transitions → apply-transitions
  → patch :agent/state + :llm/request (same exchange)
```

MCP servers are wired once at Integrant init (`:lateralus/mcp-tools` →
`mcp-registry` → merged into `:lateralus/tool-registry`). The tools
plugin **closes over** that map and reseeds `:agent/tool-registry` every
stage from the frozen snapshot. `tools/list_changed` is ignored.
Docs explicitly say **Integrant-only registration / no `add-mcp-tool!`**.

We want the agent to change MCP setup mid-session the same way it
changes model/endpoint — via allowlisted control tools + staged
transitions — without restarting.

## 2. What already works (reuse)

| Piece | Relevance |
|-------|-----------|
| Transition harvest/apply pipeline | Staging + same-exchange commit before next LLM call |
| Closed Malli ops (`Transition` multi) | Keep allowlists tight — new ops, not open maps |
| `ModelCatalog` pattern | Side-effectful I/O in tool invoke via protocol, not in pure algebra |
| `McpClient` / `McpTransport` | Existing network/process boundary + instrumentation |
| `mcp-registry` / `adapt-tools` / naming | Reuse for connect + discover; extract “one server” helpers |
| Loop reads `:agent/tool-registry` each turn | Dynamic overlay can take effect if seed/re-merge happens per stage |

## 3. Hard constraint: transitions are pure data today

`apply-transition` only `merge`s keys into session state. MCP upsert
requires **spawn/connect + handshake + list + adapt + collision check**,
and remove requires **close**. Those cannot live inside the pure algebra
without breaking the model that made `:set-llm` safe.

Two compatible patterns exist in-tree already:

1. **Pure transition** (`set_llm_config`) — tool emits data; apply merges.
2. **Protocol side effect in invoke** (`list_llm_models` → `ModelCatalog`) —
   I/O happens in the tool; no durable state change (or a separate
   transition if needed).

MCP needs **both**: effectful reconcile + durable desired-config in
session state (so the model and logs can see what is connected).

## 4. Recommended shape: Integrant-owned `McpSession` + overlay

Keep the public rule: **no `add-mcp-tool!`**. Introduce an Integrant
component that owns live MCP clients for the process/session.

```
┌─────────────────────────────────────────────────────────────┐
│ Integrant                                                     │
│  :lateralus/mcp-tools  →  McpSession (protocol)               │
│       boot-seed from :servers                                 │
│       -upsert! / -remove! / -refresh! / -registry / -status   │
│  :lateralus/tool-registry → static registries (file, web, …) │
│  :lateralus/tools-plugin → seed = static ∪ session.registry  │
└─────────────────────────────────────────────────────────────┘
                              │
          control tools       │        transitions (durable intent)
   mcp_upsert_server ─────────┼──────── {:op :mcp-upsert-server …}
   mcp_remove_server          │        {:op :mcp-remove-server …}
   mcp_refresh_server         │        {:op :mcp-refresh-server …}
   mcp_list_servers           │        (list may be read-only, no op)
```

### 4.1 Protocol (sketch)

```clojure
(defprotocol McpSession
  (-upsert-server! [this server-id server-cfg])  ;; connect+list+adapt
  (-remove-server! [this server-id])             ;; close+drop tools
  (-refresh-server! [this server-id])            ;; tools/list again
  (-registry [this])                             ;; name→Tool map
  (-status [this])                               ;; serializable inventory
  (-halt! [this]))                               ;; ig/halt-key!
```

All impl fns: Malli-instrumented inputs/outputs. Reuse
`StdioServerConfig` / `HttpServerConfig` schemas for `server-cfg`.
Test seam: inject fake `McpClient`s (same as today's `:clients`).

### 4.2 Who does the side effect?

**Preferred: side effect in the control tool invoke** (catalog-style),
then emit a **pure** transition that records durable intent:

```json
{
  "ok": true,
  "tool": "mcp_upsert_server",
  "server_id": "filesystem",
  "tools": ["filesystem_read_file", "filesystem_write_file"],
  "pending": "same-exchange",
  "transition": {
    "op": "mcp-upsert-server",
    "server-id": "filesystem",
    "config": { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp/sandbox"] }
  }
}
```

- On invoke success: session already holds live client + adapted tools.
- Transition apply: merge into `:agent/state` under e.g.
  `:mcp/servers` (desired config map, secrets redacted for model view).
- On invoke failure: no transition; return `{:ok false :error …}` —
  matches fail-fast-per-op (unlike boot, which fails the whole system).

**Alternative:** transition apply calls the session (effectful apply).
Rejected for v1 — mixes algebra purity with I/O and complicates
harvest/redact/testing. Keep `transitions.clj` pure.

### 4.3 Making new tools visible same-exchange

Today `tools-plugin` does `(assoc ctx :agent/tool-registry frozen)`.

Change seed to:

```clojure
(assoc ctx :agent/tool-registry
       (merge static-registry (mcp/-registry session)))
```

Because seed runs every stage, and ReAct follow-up re-enters compose →
inject-tools, a successful upsert in turn N is visible in turn N+1's
`:llm/request :tools` **without** stuffing Tool records into
state-delta.

Also update `apply-transitions` durable delta to record `:mcp/servers`
(config only). Optionally call `inject-tools` refresh after apply by
re-merging registry on ctx (belt-and-suspenders if seed order slips).

### 4.4 Boot vs session

| Source | Role |
|--------|------|
| EDN `:lateralus/mcp-tools :servers` | Boot seed into `McpSession` at `ig/init` |
| Dynamic upserts | Session overlay on the same component |
| `ig/halt!` | `-halt!` closes all clients (boot + dynamic) |

Operator EDN remains the air-gapped default. Dynamic adds are
**session-scoped** in v1 (lost on process exit). Persisting overlays to
disk is an explicit follow-up.

### 4.5 Closed transition ops (v1)

```clojure
{:op :mcp-upsert-server
 :server-id "…"
 :config {…}}          ; same server schemas as Integrant (no :clients)

{:op :mcp-remove-server
 :server-id "…"}

{:op :mcp-refresh-server
 :server-id "…"}       ; optional; may be tool-only with no durable change
```

`Transition` multi gains these branches. Redact `:bearer-token` /
env-resolved secrets the same way `:api-key` is redacted for `:set-llm`.

### 4.6 Control tool surface

| Tool | Effect | Transition |
|------|--------|------------|
| `mcp_upsert_server` | connect + discover | `:mcp-upsert-server` |
| `mcp_remove_server` | close + drop | `:mcp-remove-server` |
| `mcp_refresh_server` | re-`tools/list` | `:mcp-refresh-server` or none |
| `mcp_list_servers` | status inventory | none |

Wire under `:lateralus/config-tools` **or** a new
`:lateralus/mcp-session-tools` key that depends on the session
component — prefer the latter so offline/demo configs can omit them.

## 5. Alternatives considered

### A. Pure state holds full tool registry

Serialize/deserializing Tool records is impossible; holding opaque
objects in the runtime atom fights the “JSON envelope / durable keys”
story. **Reject.**

### B. Full Integrant restart / `ig/suspend` of mcp-tools only

Heavy, drops in-flight clients awkwardly, and still doesn't give the
agent a tool-shaped API. Useful as an operator escape hatch later, not
as the agent path. **Defer.**

### C. Effectful `apply-transition`

Keeps tools “propose only,” but pollutes the algebra and makes unit
tests need fakes for every apply. **Reject for v1.**

### D. Only honor `tools/list_changed` on already-configured servers

Smaller slice, but does not meet “update tool **setup**” (add/remove
servers). Good **incremental milestone** after upsert/remove.

### E. Allow open `:set-tools` maps

Too broad; violates closed-allowlist discipline. **Reject.**

## 6. Security & policy

Dynamic upsert must not weaken boot-time guards:

- Reuse HTTP SSRF / `:allow-http?` / `:allow-loopback?` checks.
- Prefer `:bearer-token-env` over inline tokens; never echo secrets in
  model-visible envelopes.
- Native-image: reject non-empty upsert (same as boot).
- Optional **allowlist** of server ids / command prefixes via
  `:lateralus/mcp-tools :dynamic` policy map (default: dynamic disabled
  until `:dynamic {:enabled? true}` — preserves air gap even if control
  tools are on the registry).
- Collision: upsert that would clash with **static** tool names
  (e.g. `web_search`) or other MCP prefixes must fail the tool invoke.
- Removing a server mid-exchange: in-flight calls to its tools should
  fail cleanly; next inject-tools omits them.

Recommended default policy:

```clojure
:lateralus/mcp-tools
{:servers {}
 :dynamic {:enabled? false}}   ; opt-in for agent-driven changes
```

## 7. Failure semantics

| Phase | Boot (`ig/init`) | Dynamic upsert |
|-------|------------------|----------------|
| One server fails | whole init throws | tool returns `ok: false`; others stay |
| Name collision | init throws | tool returns `ok: false`; no partial client left |
| Halt | close all | close removed / all on session halt |

Always leave the session consistent: on upsert failure after connect,
close the new client before returning (same pattern as
`start-server!` today).

## 8. Phased delivery

### Phase 0 — this doc
Align on protocol + transition + seed-overlay approach.

### Phase 1 — `McpSession` refactor (behavior-preserving)
Extract session component from `mcp-registry`; boot path identical;
tests green; still no dynamic tools.

### Phase 2 — refresh + list
`mcp_list_servers`, `mcp_refresh_server`; honor optional
`tools/list_changed` later.

### Phase 3 — upsert / remove + transitions
Control tools + closed ops + dynamic policy gate + same-exchange
visibility via live registry merge.

### Phase 4 — demos / docs / e2e
Fake stdio + HTTP demos that start empty and upsert mid-session;
update `docs/transitions.md`, `docs/mcp.md`, architecture extension
notes.

## 9. Open questions for the human

1. **Policy default:** should dynamic MCP be opt-in
   (`:dynamic {:enabled? false}`) even when control tools are registered?
2. **Scope of `:config` in upsert:** full Integrant server map, or a
   reduced agent-facing subset (e.g. no `:env` arbitrary maps)?
3. **Static vs dynamic precedence:** if EDN defines `filesystem` and the
   agent upserts the same id, replace or reject?
4. **Same-exchange requirement:** is “next exchange only” acceptable for
   v1, or must ReAct follow-ups see new tools immediately (recommended:
   immediate, via live merge)?
5. **Where do control tools live:** extend `:lateralus/config-tools` or
   new `:lateralus/mcp-session-tools`?
6. **Generalize now?** Name the session overlay pattern so web/runtime
   tools can plug in later, or keep MCP-specific until proven?

## 10. Suggested decision (defaults if unblocked)

| Question | Default |
|----------|---------|
| Policy | Dynamic **disabled** until `:dynamic {:enabled? true}` |
| Config shape | Reuse existing server schemas; redact secrets |
| Same id as boot | **Replace** (upsert semantics) after closing old client |
| Visibility | **Same-exchange** via `static ∪ session.registry` seed |
| Tool key | New `:lateralus/mcp-session-tools` depending on session |
| Generalize | MCP-specific protocol first; extract “ToolOverlay” only if a second consumer appears |

## 11. Implementation touch points (when building)

| Area | Change |
|------|--------|
| `tools/mcp/protocol.clj` | add `McpSession` |
| `tools/mcp/tools.clj` / new `session.clj` | refactor registry build into session |
| `system.clj` | init/halt session; wire session tools |
| `plugins/tools.clj` | merge live MCP registry each seed |
| `transitions.clj` | new closed ops + redact |
| `tools/config.clj` or `tools/mcp/session_tools.clj` | control tools |
| `docs/transitions.md`, `docs/mcp.md` | document ops + policy |
| tests | unit (fake client) + e2e fake upsert mid-loop |

## 12. One-paragraph summary

Treat MCP like LLM config at the **agent API** layer (closed transitions +
control tools), but like `ModelCatalog` at the **I/O** layer (protocol
owned by Integrant). Keep `transitions.clj` pure; put connect/close in
`McpSession`; reseeds of `:agent/tool-registry` merge a live session
slice so ReAct sees new tools next call — without ever exposing
`add-mcp-tool!`.
