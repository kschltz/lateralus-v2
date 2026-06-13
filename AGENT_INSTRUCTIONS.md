# AGENT_INSTRUCTIONS

## Canonical Sources

- **Active goal:** `goals/lateralus-v2-rewrite/goal.md` → `facts.md` → `plan.md`
- **Architecture thesis:** `docs/interceptor-loop-implementation-plan.md`
- **Memory v2 schema:** `docs/memory-v2.md`
- **v1 reference (archive):** sibling repo `../lateralus/` — port seed code only, do not copy `core.clj` or `loop.clj`
- **Historical goals/plans:** `goals/lateralus-file-editing/`, `docs/arch-remediation-plan.md`, `docs/clj-edit-implementation-plan.md`, `docs/memory-system-mvi.md`

## The One Rule

All agent behavior flows through an **interceptor chain** on an **immutable context map**. State changes stage in `:agent/state-delta`; only the outer runtime loop merges into the agent ref. External/network I/O uses **protocols + Malli instrumentation**. Extension is **Integrant-managed plugins only** — no `add-*-tool!` functions.

## Architecture (locked)

| Item | Value |
|------|-------|
| Coord | `net.clojars.kschltz/lateralus-v2` |
| Main ns | `kschltz.lateralus` |
| Agent ns | `kschltz.agent.*` |
| Integrant config | `resources/lateralus/config.edn` |

Portable v1 seed: `chain.clj`, `plugin.clj`, `interceptors/schema.clj`, `interceptors.clj`, `context.clj`, `llm/client.clj`, `exchange.clj` — rewrite interceptors to remove `loop/` delegation.

## MVP Scope

Core loop (empty tool registry, stub-tested dispatch) + session memory (v2 format) + clean-slate CLI + JVM distributable. GraalVM native-image is stretch (Step 9). No v1 tools in MVP.

## Verify

```bash
clojure -M:test -m cognitect.test-runner          # all tests green
clojure -T:build uber                             # JVM distributable
rg 'add-.*-tool!' src/                            # no matches
rg 'http/completion' src/                         # only in llm/http layer (once implemented)
rg 'agent\.loop' src/                             # no loop.clj dependency in interceptors
```

Follow `goals/lateralus-v2-rewrite/plan.md` step order. No feature ships without integration tests.
