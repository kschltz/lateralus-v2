# Plan — Dynamic MCP tool setup

See [`docs/dynamic-mcp-tool-setup.md`](../../docs/dynamic-mcp-tool-setup.md).

## Phase 0

- [x] Goal doc
- [x] Design exploration
- [x] Human ack (implement)

## Phase 1 — behavior-preserving `McpSession`

- [x] Extract `McpSession` protocol + impl from `mcp-registry`
- [x] Integrant init seeds from `:servers`; halt closes clients
- [x] `mcp-registry` convenience retained for tests

## Phase 2 — list + refresh

- [x] `mcp_list_servers`, `mcp_refresh_server`

## Phase 3 — upsert / remove

- [x] `:dynamic` policy gate (default on in JVM configs; native off)
- [x] Control tools + closed transition ops
- [x] `tools-plugin` merges `static ∪ session.registry` each seed
- [x] Same-exchange visibility in ReAct follow-ups
- [x] `:mcp/servers` replace-on-merge in runtime

## Phase 4 — demos / docs / e2e

- [x] Docs updated (`transitions`, `mcp`, design doc)
- [x] Unit tests for session + transitions
- [x] Ollama Cloud mid-session upsert demo
  (`scripts/demo-ollama-cloud-mcp-dynamic-pty.py`)
