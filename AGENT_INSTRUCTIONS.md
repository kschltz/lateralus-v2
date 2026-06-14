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

Core loop (empty tool registry, stub-tested dispatch) + session memory (`MemoryBackend` protocol with noop and Proximum implementations; runtime default is Proximum + LangChain4j in-process ONNX embedder) + clean-slate CLI + JVM distributable. GraalVM native-image is stretch (Step 9). No v1 tools in MVP. **No Datalevin in MVP.**

## Verify

```bash
clojure -M:test                                 # default suite (excludes ^:e2e)
clojure -T:build test                           # same suite via tools.build
clojure -M:e2e                                  # end-to-end memory tests
LATERALUS_E2E_FAKE=true clojure -M:e2e          # deterministic fake-server e2e
clojure -T:build uber                           # JVM distributable
./target/lateralus-v2 -h                      # smoke-test launcher
rg 'add-.*-tool!' src/                          # no matches
rg 'http/completion' src/                       # only in llm/http.clj
rg 'agent\.loop' src/                            # no loop.clj dependency in interceptors
```

When editing `build.clj`, `deps.edn`, or `resources/lateralus/config.edn`, run
`~/.local/bin/clj-paren-repair PATH` after paren-sensitive changes.

Follow `goals/lateralus-v2-rewrite/plan.md` step order. No feature ships without integration tests.

## MVP status

- Steps 1–6, 7–8, and 10 are implemented.
- Step 6 ships the memory plugin interceptors, a noop `MemoryBackend`, and a **Proximum** HNSW backend with **LangChain4j in-process ONNX embedding** as the runtime default.
- Step 9 (GraalVM native-image) is a deferred follow-up.
- Step 10 (docs + quality gate) is complete.
