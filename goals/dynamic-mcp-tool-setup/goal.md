# Goal — Dynamic MCP tool setup via runtime transitions

## Intent

Extend Lateralus's **allowlisted session-state transition** mechanism
(today: `set_llm_config` / `:set-llm`) so the agent can also **change its
tool setup at runtime**, starting with **MCP server configuration** as
the base use case.

Operators (and the agent, via control tools) should be able to add,
remove, refresh, and inspect MCP servers mid-session — without restarting
the JVM or re-running Integrant `ig/init` — while preserving the existing
architecture invariants:

- tools return strings only
- runtime atom is the single writer of durable session state
- network/process I/O stays behind protocols + Malli instrumentation
- no public `add-mcp-tool!` / `add-*-tool!` mutation APIs
- air-gapped default remains empty MCP servers

## Why MCP first

MCP is the hardest "dynamic tool" case that still fits the transition
pattern:

| Concern | LLM config (`:set-llm`) | MCP tool setup |
|---------|-------------------------|----------------|
| Payload | pure data keys | data + live clients |
| Side effects | none in apply | spawn/connect, handshake, `tools/list`, close |
| Surface to LLM | request knobs | `:agent/tool-registry` + `:llm/request :tools` |
| Lifecycle | none | must halt on remove / session stop |
| Failure modes | invalid string | spawn/handshake/SSRF/collision |

If MCP works, lighter cases (toggling web tools, swapping runtime-eval
guards) can reuse the same overlay/reconcile shape.

## Success criteria (exploration → later implementation)

1. Design documents a path from Integrant-static MCP registration to
   session-dynamic MCP without introducing `add-mcp-tool!`.
2. Closed transition ops (or an equivalent control-tool + protocol
   pattern) cover upsert / remove / refresh / list for MCP servers.
3. Same-exchange ReAct follow-ups can see newly discovered tools on the
   next LLM call (mirror `set_llm_config` timing).
4. Static EDN `:lateralus/mcp-tools` remains the boot seed; dynamic
   changes are session-scoped overlays unless we later add persistence.
5. Security defaults (SSRF, size caps, injection guards, native-image
   empty servers) are unchanged or stricter for dynamic adds.
6. Failures are model-visible JSON envelopes — no silent half-registry.

## Non-goals (v1 of this feature)

- Hot-reloading Integrant EDN / file watch of `config.edn`
- Swapping non-MCP registries wholesale (web, file, runtime-eval)
- MCP resources / prompts / OAuth
- Persisting dynamic MCP config across process restarts
- Auto-import of Claude Desktop / Cursor `mcp.json`

## Related docs

- [`docs/transitions.md`](../../docs/transitions.md) — current transition algebra
- [`docs/mcp.md`](../../docs/mcp.md) — MCP client today (Integrant-only)
- [`docs/architecture.md`](../../docs/architecture.md) — runtime / plugins
- [`docs/dynamic-mcp-tool-setup.md`](../../docs/dynamic-mcp-tool-setup.md) — design exploration
