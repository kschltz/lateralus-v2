# AGENT_INSTRUCTIONS

## Canonical Sources

- **Active goal:** `goals/lateralus-v2-rewrite/goal.md` → `facts.md` → `plan.md`
- **Follow-up goal (MCP client tools):** `goals/mcp-client-tools/goal.md` → `plan.md`
- **MCP client tool:** `docs/mcp.md` (`tools.mcp`, `:lateralus/mcp-tools`, stdio servers, air-gapped default)
- **Architecture overview:** `docs/architecture.md`
- **Interceptor-chain design note (superseded thesis):** `docs/interceptor-loop-design-note.md`
- **Web tool:** `docs/web.md` (`tools.web`, `:none` default, `:mojeek`/`:ddg` opt-in, `:lateralus/web-tools` Integrant key)
- **Session config / transitions:** `docs/transitions.md` (`transitions`, `set_llm_config` + `list_llm_models`, `ModelCatalog`, `:lateralus/config-tools`)
- **Follow-up goal (dynamic MCP tool setup):** `goals/dynamic-mcp-tool-setup/goal.md` → `plan.md` — design: `docs/dynamic-mcp-tool-setup.md`
- **Runtime-eval tool:** `docs/runtime-eval.md` (`tools.runtime`, `clojure_eval` + `clojure_add_lib` + `clojure_loaded_libs`, `ClojureRuntime` protocol, `:lateralus/runtime-tools` Integrant key)
- **Network boundary matrix:** `docs/network-boundaries.md` (protocol isolation + Malli instrumentation)
- **Memory v2 schema:** `docs/memory-v2.md`
- **Docker / workbench ship:** `docker/README.md`, `./scripts/start-workbench` (profile gate + CHAT\|Portal; Portal `:7870`)
- **CLI profiles:** `~/.config/lateralus/` via `kschltz.agent.cli.profile.*` (no `--config` → interactive gate; secrets via `OLLAMA_API_KEY` only)
- **v1 reference (archive):** https://github.com/kschltz/lateralus — port seed code only, do not copy `core.clj` or `loop.clj`
- **Historical goals/plans (archived):** `goals/lateralus-file-editing/`, `docs/archive/arch-remediation-plan.md`, `docs/archive/clj-edit-implementation-plan.md`, `docs/archive/memory-system-mvi.md`

## The One Rule

All agent behavior flows through an **interceptor chain** on an **immutable context map**. State changes stage in `:agent/state-delta`; only the outer runtime loop merges into the agent ref. External/network I/O uses **protocols + Malli instrumentation**. Extension is **Integrant-managed plugins only** — no `add-*-tool!` functions. New capabilities ship with attack guards where the model consumes untrusted external content.

## Architecture (locked)

| Item | Value |
|------|-------|
| Coord | `net.clojars.kschltz/lateralus-v2` |
| Main ns | `kschltz.lateralus` |
| Agent ns | `kschltz.agent.*` |
| Integrant config | `resources/lateralus/config.edn` |

Portable v1 seed: `chain.clj`, `plugin.clj`, `interceptors/schema.clj`, `interceptors.clj`, `context.clj`, `llm/client.clj`, `exchange.clj` — rewrite interceptors to remove `loop/` delegation.

## MVP Scope

Core loop (tool-calling loop in base plugin, empty registry by default, filesystem tools in example configs) + session memory (`MemoryBackend` protocol with noop and Proximum implementations; runtime default is Proximum + LangChain4j in-process ONNX embedder) + clean-slate CLI + JVM distributable. GraalVM native-image is implemented (Step 9). No v1 tools in MVP. **No Datalevin in MVP.**

## Verify

```bash
clojure -M:test                                 # default suite (excludes ^:e2e)
clojure -T:build test                           # same suite via tools.build
clojure -M:e2e                                  # end-to-end memory tests
LATERALUS_E2E_FAKE=true clojure -M:e2e          # deterministic fake-server e2e (incl. MCP)
clojure -M:e2e:workbench -n kschltz.agent.runtime-harness-e2e-test # offline runtime/file harness
LATERALUS_E2E_MCP=live clojure -M:e2e -n kschltz.agent.tools.mcp.mcp-e2e-test
clojure -T:build uber                           # JVM distributable (includes :workbench)
./target/lateralus-v2 -h                        # smoke-test launcher
./scripts/start-workbench                       # Docker: Ollama + profile gate + workbench
clojure -M:workbench:run -i                     # local workbench (JVM flags in alias)
rg 'add-.*-tool!' src/                          # no matches
rg 'http/completion' src/                       # only in llm/http.clj
rg 'agent\.loop' src/                            # no loop.clj dependency in interceptors
rg 'web_search|web-search|ddg|duckduckgo' -- 'README.md' 'docs/' 'AGENT_INSTRUCTIONS.md' 'src/' 'resources/' 'test/'  # only archive references
rg '\(first\s+url-check\)' src/ test/             # prior URL-guard bug must stay fixed
rg 'max-history-entries' src/ test/              # cap is 100 (not the legacy 40)
rg 'history-summarize' src/                      # summarizer is wired into the default chain
```

When editing `build.clj`, `deps.edn`, or `resources/lateralus/config.edn`, run
`~/.local/bin/clj-paren-repair PATH` after paren-sensitive changes.

Follow `goals/lateralus-v2-rewrite/plan.md` step order. No feature ships without integration tests.

## MVP status

- Steps 1–6, 7–8, and 10 are implemented.
- Step 6 ships the memory plugin interceptors, a noop `MemoryBackend`, and a **Proximum** HNSW backend with **LangChain4j in-process ONNX embedding** as the JVM runtime default. A **KG + BM25** backend is the native-image default.
- Step 9 (GraalVM native-image) is implemented using the KG + BM25 backend and a noop HTTP embedder, with Proximum / LangChain4j sources excluded from the filtered native classpath.
- Step 10 (docs + quality gate) is complete.

## Doc freshness policy

When a Kanban card changes architecture (new Integrant keys, plugin slots, protocol surface, default config, or tool surface), update `docs/architecture.md`, `README.md`, and the relevant tool doc (`docs/web.md` for web tools, `docs/mcp.md` for MCP client tools, `docs/memory-v2.md` for memory) before advancing the card.
