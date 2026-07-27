# Plan — Dynamic MCP tool setup

Exploration only until Phase 0 questions in
[`docs/dynamic-mcp-tool-setup.md`](../../docs/dynamic-mcp-tool-setup.md)
§9 are answered (or defaults in §10 accepted).

## Phase 0 (this PR)

- [x] Goal doc
- [x] Design exploration (constraints, recommended shape, alternatives,
      security, phases, open questions)
- [ ] Human ack on §9 / §10 defaults

## Phase 1 — behavior-preserving `McpSession`

- Extract `McpSession` protocol + impl from `mcp-registry`
- Integrant init seeds from `:servers`; halt closes clients
- No new tools; existing MCP tests remain green

## Phase 2 — list + refresh

- `mcp_list_servers`, `mcp_refresh_server`
- Optional later: `tools/list_changed`

## Phase 3 — upsert / remove

- `:dynamic` policy gate (default off)
- Control tools + closed transition ops
- `tools-plugin` merges `static ∪ session.registry` each seed
- Same-exchange visibility in ReAct follow-ups

## Phase 4 — demos / docs / e2e

- Mid-session upsert demo (fake stdio + HTTP)
- Update `docs/transitions.md`, `docs/mcp.md`, architecture notes
