# AGENT_INSTRUCTIONS

## Canonical Sources

- **Active goal:** `goals/lateralus-v2-rewrite/goal.md` → `facts.md` → `plan.md`
- **Follow-up goal (MCP client tools):** `goals/mcp-client-tools/goal.md` → `plan.md`
- **MCP client tool:** `docs/mcp.md` (`tools.mcp`, `:lateralus/mcp-tools`, stdio + Streamable HTTP servers, air-gapped default)
- **Architecture overview:** `docs/architecture.md`
- **Web tool:** `docs/web.md` (`tools.web`, `:none` default, `:mojeek`/`:ddg` opt-in, `:lateralus/web-tools` Integrant key)
- **Runtime tool factory + workflow engine:** `docs/runtime-tools.md` (`tool_define`/`tool_promote`, `:lateralus/factory-tools`, `:needs`/`:produces` artifact DAGs, `:lateralus/workflow-tools`)
- **Workbench (CHAT | Portal) + 2-way Portal loop:** `docs/workbench-2way.md` (`workbench/` ns group, `portal_*` tools, `/api/portal-event` call-back, `portal_selected` read-back, settings/secrets/sessions HTTP surfaces)
- **Session config / transitions:** `docs/transitions.md` (`transitions`, `set_llm_config` + `list_llm_models`, `ModelCatalog`, `:lateralus/config-tools`)
- **Follow-up goal (dynamic MCP tool setup):** `goals/dynamic-mcp-tool-setup/goal.md` → `plan.md` — design: `docs/dynamic-mcp-tool-setup.md`
- **Runtime-eval tool:** `docs/runtime-eval.md` (`tools.runtime`, `clojure_eval` + `clojure_add_lib` + `clojure_loaded_libs`, `ClojureRuntime` protocol, `:lateralus/runtime-tools` Integrant key)
- **Skill packs:** `docs/skills.md` (`kschltz.agent.skills`, `:lateralus/skills-store` + `:lateralus/skills-plugin`, `.edn` skill schema, progressive disclosure)
- **Secrets plugin:** `docs/secrets.md` (`kschltz.agent.secrets`, `:lateralus/secret-store` + `:lateralus/secret-plugin`, use-without-seeing)
- **Network boundary matrix:** `docs/network-boundaries.md` (protocol isolation + Malli instrumentation)
- **Memory v2 schema:** `docs/memory-v2.md`
- **DuckDB core-store options:** `docs/duckdb-core-engine.md` (persistence substrate behind existing protocols; not a chain replacement)
- **Workspace file index:** `docs/file-index.md` (`:lateralus/store` + `:lateralus/file-index`, opt-in)
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

Portable v1 seed namesakes: `chain.clj`, `plugin.clj`, `interceptors/schema.clj`, `interceptors.clj`, `llm/client.clj` — v1 `context.clj` / `exchange.clj` were dissolved into `runtime.clj` + the chain stages.

## Scope (past MVP — current reality)

The MVP is complete; the system now ships: the tool-calling loop in the base
plugin, Proximum HNSW + LangChain4j ONNX memory defaults (KG + BM25 for
native-image), the interactive workbench (sessions/settings/secrets UI), the
2-way Portal loop, web tools with SSRF/injection guards, MCP client +
mid-session `mcp_*` management tools, the runtime tool factory, the workflow
artifact engine, secrets and skills plugins (both opt-in), and the full
file/clojure filesystem+structured-edit harness. GraalVM native-image is
implemented (Step 9). No v1 tools. **No Datalevin.**

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

- Steps 1–10 are implemented (bootstrap → docs + quality gate).
- Step 6 ships the memory plugin interceptors and the **Proximum** HNSW /
  **LangChain4j in-process ONNX** JVM defaults; **KG + BM25** is the
  native-image default.
- Step 9 (GraalVM native-image) uses the KG + BM25 backend, with Proximum /
  LangChain4j / live web sources excluded from the filtered native classpath.
- Recent work: workbench secrets-management UI (`/api/secrets`, values never
  served back), `portal_selected` + `/api/portal-event` 2-way Portal loop,
  skills + secrets plugins (Malli-closed, fail-closed, opt-in), dynamic tool
  factory + workflow engine.

## Doc freshness policy

When a Kanban card changes architecture (new Integrant keys, plugin slots,
protocol surface, default config, or tool surface), update
`docs/architecture.md`, `README.md`, and the relevant tool doc (`docs/web.md`
for web tools, `docs/mcp.md` for MCP client tools, `docs/memory-v2.md` for
memory, `docs/secrets.md` for secrets, `docs/skills.md` for skills,
`docs/workbench-2way.md` for the Portal loop) before advancing the card.
