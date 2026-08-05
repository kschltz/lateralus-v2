# Design exploration — Dynamic MCP tool setup

Status: **implemented** (Phases 1–3).  
Companion goal: [`goals/dynamic-mcp-tool-setup/goal.md`](../goals/dynamic-mcp-tool-setup/goal.md).

## Summary

MCP tool setup can change mid-session via allowlisted control tools +
transitions, without `add-mcp-tool!`:

1. **`McpSession`** (`kschltz.agent.tools.mcp.session`) — Integrant-owned;
   connect / close / refresh / live registry.
2. **Control tools** (`mcp_list_servers`, `mcp_upsert_server`,
   `mcp_remove_server`, `mcp_refresh_server`) — mutating tools are **pure
   proposers** (same monadic pattern as `set_llm_config`); they emit
   transition envelopes only. `mcp_list_servers` is read-only.
3. **Apply/reconcile** — harvest enqueues ops; apply reconciles live
   `McpSession` I/O, records durable `:mcp/servers` intent, and rewrites
   tool results with discovered tools or errors (before compose).
4. **`tools-plugin`** — seeds `static ∪ session.registry` every stage so
   ReAct follow-ups see new tools same-exchange.
5. **Policy** — `:dynamic {:enabled? true}` by default in JVM runtime
   configs; set false to lock upsert/remove. List/refresh always allowed
   for connected servers. Native-image configs keep dynamic off.

## Config shape

```clojure
:lateralus/mcp-tools
{:servers {}                          ;; boot seed (air-gapped default)
 :dynamic {:enabled? true}}           ;; default on; set false to lock

:lateralus/mcp-session-tools
{:session #ig/ref :lateralus/mcp-tools}

:lateralus/tool-registry
[… #ig/ref :lateralus/mcp-session-tools]   ;; control tools only

:lateralus/tools-plugin
{:registry #ig/ref :lateralus/tool-registry
 :mcp-session #ig/ref :lateralus/mcp-tools} ;; live MCP tool overlay
```

Boot-discovered MCP tools are **not** frozen into `:lateralus/tool-registry`;
they come from the session on every seed.

## Transition ops

```clojure
{:op :mcp-upsert-server :server-id "…" :config {…}}  ; redacted config
{:op :mcp-remove-server :server-id "…"}
{:op :mcp-refresh-server :server-id "…"}             ; no durable config change
```

`:mcp/servers` in session state is replaced wholesale on runtime merge
(so removals drop keys). Secrets (`:bearer-token`, `:env`) are redacted
in model-visible envelopes.

## Defaults accepted

| Question | Default |
|----------|---------|
| Policy | Dynamic **enabled** by default; `:dynamic {:enabled? false}` locks |
| Config shape | Reuse existing server schemas; redact secrets |
| Same id as boot | **Replace** (upsert) after closing old client |
| Visibility | **Same-exchange** via live registry merge |
| Tool key | `:lateralus/mcp-session-tools` |
| Generalize | MCP-specific for now |

## Non-goals (still deferred)

- Hot-reload of EDN / file watch
- Persisting dynamic MCP config across process restarts
- `tools/list_changed` notifications
- Swapping non-MCP registries wholesale
