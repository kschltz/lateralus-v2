# Lateralus v2 Architecture

This document describes the current Integrant + plugin/interceptor architecture of `lateralus-v2`. For the original thesis and historical context, see [`docs/interceptor-loop-design-note.md`](docs/interceptor-loop-design-note.md). For the memory subsystem, see [`docs/memory-v2.md`](docs/memory-v2.md). Outbound protocol and Malli requirements are indexed in [`docs/network-boundaries.md`](network-boundaries.md).

## Overview

Lateralus v2 is a single-user LLM agent built around three ideas:

1. **Interceptor chain engine** — every stage of an exchange (safety, recall, context composition, LLM call, response parsing, dispatch, persistence, delivery) is a pure interceptor that receives and returns an immutable context map. The engine is `kschltz.agent.chain`.
2. **Integrant lifecycle** — all extensible components (LLM client, embedder, memory backend, plugins) are Integrant keys. The system is started with `ig/init` and shut down with `ig/halt!`.
3. **Thin outer runtime** — `kschltz.agent.runtime` creates a per-exchange context, runs the assembled chain, and merges `:agent/state-delta` into an in-memory atom. The CLI, tests, and any future web server all call this same runtime.

## Component graph

```
┌─────────────────────────────────────────────────────────────────┐
│                       CLI / -main / tests                       │
│                         (kschltz.agent.cli)                      │
└──────────────────────────────────┬──────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────┐
│                     Integrant system                            │
│  :lateralus/llm-client   ──▶  LlmClient protocol (stub / http)   │
│  :lateralus/llm-config     ──▶  raw opts (base-url / api-key / model)│
│  :lateralus/embedder      ──▶  Embedder protocol (noop / http / langchain4j)│
│  :lateralus/memory-backend ──▶ MemoryBackend protocol (noop / proximum / kg-bm25)│
│  :lateralus/memory-plugin ──▶  memory recall + persist slots    │
│  :lateralus/file-tools       ──▶  convenience filesystem tool registry│
│  :lateralus/self-awareness-tools ──▶  self_status tool registry      │
│  :lateralus/config-tools     ──▶  set_llm_config + list_llm_models (ModelCatalog)│
│  :lateralus/clojure-tools    ──▶  clojure structured-edit tool registry│
│  :lateralus/runtime-tools    ──▶  ClojureRuntime tool registry (clojure_eval, add_lib, loaded_libs)│
│  :lateralus/web-tools        ──▶  web `Tool` registry (web_search, web_fetch, web_extract)│
│  :lateralus/mcp-tools        ──▶  McpSession (boot seed + live overlay)   │
│  :lateralus/mcp-session-tools──▶  mcp_* control tools                     │
│  :lateralus/factory-session   ──▶  RuntimeToolStore (tool_define overlay) │
│  :lateralus/factory-tools     ──▶  tool_define / forget / list / promote  │
│  :lateralus/factory-plugin    ──▶  seed session + runtime interceptors    │
│  :lateralus/stream-bus        ──▶  live/historic response metadata        │
│  :lateralus/stream-plugin     ──▶  wrap LLM + emit thinking/token events  │
│  :lateralus/workflow-tools    ──▶  workflow_register/seed/run/status/clear│
│  :lateralus/tool-registry     ──▶  merged vector of tool-name -> Tool registries  │
│  :lateralus/tools-plugin      ──▶  seeds `:agent/tool-registry` (+ MCP + factory) │
│  :lateralus/cli-ui            ──▶  optional CliRenderer (prompt/response colors; not a chain plugin) │
│  :lateralus/plugins       ──▶  assembled plugin maps (base plugin auto-prepended) │
│  :lateralus/agent         ──▶  agent-map + exchange-chain + pre-wired deps │
└──────────────────────────────────┬──────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Agent runtime                                │
│              (kschltz.agent.runtime)                              │
│  start(agent-map, session-id)                                   │
│  send-message(runtime, user-text)                                │
│  stop(runtime)                                                  │
└──────────────────────────────────┬──────────────────────────────┘
                                     │
                                     │ builds per-exchange ctx
                                     │ with traceability IDs
                                     ▼
┌─────────────────────────────────────────────────────────────────┐
│                  Interceptor chain engine                         │
│                    (kschltz.agent.chain)                          │
│                                                                 │
│  execute(ctx, assembled-chain)                                 │
│                                                                 │
│  Assembled from plugin slots (see default-slot-order below):      │
│  :guard    → error-boundary                                      │
│  :enrich   → memory recall (when memory plugin present)          │
│  :compose  → compose-context, inject-tools                       │
│  :llm      → llm-call-with-self-heal, llm-call, parse-response    │
│  :tools    → dispatch-tools, harvest-transitions, apply-transitions, compose-tool-results │
│  :finalize → tool-loop-interceptor                                │
│  :history-summarize → summarize-history (compacts long histories) │
│  :history  → store-exchange                                      │
│  :persist  → memory persist (when memory plugin present)         │
│  :observe  → deliver-responses                                   │
│  :notify   → notify                                              │
└─────────────────────────────────────────────────────────────────┘
```

## Plugin slot vocabulary

Slots are declared in `kschltz.agent.plugin/default-slot-order` and folded by `plugin/assemble-chain`:

```clojure
[:guard :enrich :compose :llm :dispatch
 :tools :finalize
 :history-summarize :history :persist :observe :notify]
```

`:history-summarize` is placed BEFORE `:history` because leave stages run
stack-reverse: `summarize-history` enters before `store-exchange` and
leaves after it, so its `:leave` sees the freshly-written
`:agent/history` in `:agent/state-delta` and can compact it. See
`docs/memory-v2.md` § History summarization policy.

| Slot | Phase | Typical use | Wired by |
|------|-------|-------------|----------|
| `:guard` | enter | safety / safety checks before compose | base plugin (`error-boundary`) |
| `:enrich` | enter | RAG / memory recall before compose | memory plugin |
| `:compose` | enter | build `:llm/request` from state + recall + user text | base plugin (`compose-context`) |
| `:llm` | enter | call the LLM, parse response | base plugin (`llm-call-with-self-heal`, `llm-call`, `parse-response`) |
| `:dispatch` | enter | reserved slot (no interceptor wired) | — |
| `:tools` | enter | dispatch tools; harvest/apply staged state transitions (incl. MCP reconcile); compose tool results | base plugin (`dispatch-tools`, `harvest-transitions`, `apply-transitions`, `compose-tool-results`) |
| `:finalize` | enter | tool loop, act-nudge on plan-only replies, ensure-text | base plugin (`tool-loop`, `ensure-text-response`) |
| `:history-summarize` | leave | compact long `:agent/history` into one summary + protected window | base plugin (`summarize-history`); optional `summarizer-plugin` overrides the LlmClient |
| `:history` | leave | record exchange history | base plugin (`store-exchange`) |
| `:persist` | leave | memory / state persistence | memory plugin |
| `:observe` | leave | tracing / metrics / outgoing queue | base plugin (`deliver-responses`) |
| `:notify` | leave | event callbacks (UI, telemetry) | base plugin (`notify`) |

Within a slot, plugin declaration order determines interceptor order. The base plugin is always prepended by `system/init-key :lateralus/plugins`, so the default chain is always present and user plugins assemble around it.

## The context map

The context is an open map. Engine state (`::chain/queue`, `::chain/stack`, `::chain/error`) is namespaced so it never collides with domain keys. Domain keys of interest:

| Key | Set by | Read by | Meaning |
|-----|--------|---------|---------|
| `:exchange/session-id` | runtime | all stages | stable session identifier |
| `:exchange/user-msg-id` | runtime | all stages | UUID for this user turn |
| `:exchange/assistant-msg-id` | runtime | all stages | UUID for the assistant response |
| `:exchange/user-text` | runtime | compose-context, memory plugin | the user's prompt |
| `:agent/state` | runtime | compose-context | persistent state (LLM config, system message, history) |
| `:agent/agent-map` | runtime | runtime inspection, sub-agent tools | active redacted-by-consumer runtime descriptor source |
| `:agent/exchange-chain` | runtime | runtime inspection | exact ordered interceptor vector executing this exchange |
| `:llm/client` | runtime | llm-call | Integrant-configured LlmClient (pre-wired from agent-map) |
| `:embedder` | runtime | (available to interceptors) | Integrant-configured Embedder (pre-wired from agent-map) |
| `:memory/backend` | runtime | (available to interceptors) | Integrant-configured MemoryBackend (pre-wired from agent-map) |
| `:llm/request` | compose-context | llm-call | OpenAI-shaped request body |
| `:llm/response` | llm-call | parse-response | raw provider response |
| `:exchange/response` | parse-response | deliver-responses, memory plugin | final assistant text |
| `:memory/recall` | memory plugin | compose-context | recalled messages for context injection |
| `:agent/state-delta` | any stage | runtime | reserved for state changes to merge after the exchange |
| `:agent/tool-registry` | tools-plugin / runtime | compose-context, loop interceptors | map of tool name -> Tool implementation |
| `:tool/calls` | parse-response / loop | dispatch-tools-interceptor | tool calls parsed from the LLM response |
| `:tool/results` | dispatch-tools-interceptor | compose-tool-results-interceptor, tool-loop-interceptor | tool execution results |
| `:agent/all-tool-results` | compose-tool-results-interceptor | compose-context | accumulated tool results for follow-up turns |

A non-blank assistant reply with **no** `tool_calls` used to end the exchange (the workbench then parks on the next human message). When that text is a planning announcement (`I'll implement…`, `here's the plan`), `loop.act` nudges and re-enters the ReAct follow-up **with tools still attached**, so the model can implement in the same user turn.

The `Ctx` Malli schema in `kschltz.agent.interceptors.schema` is intentionally open: it validates only a few instrumentation and traceability keys, leaving domain keys free for plugins to extend without a schema migration.

## State and state-delta

Only the outer runtime loop holds a mutable reference — an atom seeded with `:initial-state` from the agent-map. Interceptors never mutate shared refs; instead they emit `:agent/state-delta`. The runtime merges this delta into the atom using `kschltz.agent.runtime/merge-state`, which performs a deep merge for known nested keys (e.g. `:agent/state`) and last-write-wins for top-level keys.

Tools may propose allowlisted **transitions** (JSON envelope key `:transition`) that are harvested onto `:agent/transitions` and applied in the `:tools` slot before the next LLM call — including mid-ReAct follow-ups. Current pure-data transitions cover LLM knobs, the system message, loop policy, memory policy, and the session tool overlay; lifecycle-bearing MCP changes add a protocol reconcile step. See [`docs/transitions.md`](transitions.md).

## Filesystem harness

`:lateralus/file-tools` exposes bounded, model-oriented reads and safe
mutations through the normal `Tool` dispatch interceptor. `file_read` returns
line-numbered windows plus the SHA-256 of the exact bytes consumed. Callers can
carry that digest into `file_write` as `expected-sha256`; the writer returns a
structured `stale-file` conflict instead of overwriting a changed file.
Read, list, info, and search operations resolve canonical paths under the
workspace, reject blocked segments, and never follow a workspace symlink to an
outside target unless the operator explicitly enables outside-workspace reads.
Directory listings are sorted and capped with total/truncation metadata.
`file_glob` adds sorted, bounded file discovery using portable glob patterns;
it excludes blocked trees and does not follow directory symlinks.
`file_patch` consumes the SHA-256 witness from `file_read` and applies
inclusive 1-based line-range replacements (or insertion ranges) to that exact
snapshot. It validates all ranges before a locked, backed-up, atomic commit;
stale hashes, overlap, invalid UTF-8, size/omission violations, and malformed
Clojure output leave the target unchanged.

`file_write`, `file_update`, and `file_create` share one mutation boundary:
canonical workspace containment (including symlink resolution), unskippable
blocked paths, per-path locking, size and omission checks, optional Clojure
round-trip validation, backups for replacements, atomic moves, and post-write
verification. `file_create` is strictly create-only and refuses an existing
path. `file_update` validates all edits before taking the lock and rechecks a
staleness sentinel at commit, so failed or racing edits produce zero writes.

The rewrite-clj-backed `clojure_*` tools share the same canonical containment,
blocked-path, per-path lock, timestamped backup, atomic-landing, optimistic
concurrency, and verification primitives. Their additional round-trip parse
guard runs before commit, and non-Clojure targets receive a structured routing
error instead of being edited as source.
The adjacent `clojure_lint` tool runs a bounded local clj-kondo subprocess
against policy-validated paths and returns structured findings without
modifying files. It introduces no network boundary and degrades explicitly
when the optional executable is unavailable.

## Runtime introspection

The `runtime_describe` tool reads the current immutable interceptor context and
returns a redacted descriptor of the session summary, loop policy, state keys,
registered tool contracts, ordered interceptor chain, and a self-update
playbook. The outer runtime injects `:agent/agent-map` and `:agent/exchange-chain`
into each exchange context; the tool selects safe data from those values and
never returns API keys or live implementation objects. Its `section` input
(`summary`, `tools`, `chain`, `playbook`, or `all`) lets the model bound output.

This is inspection only. Runtime changes still use allowlisted transition
envelopes harvested and applied in the `:tools` interceptor slot.

After source edits, `reload_runtime` can stage a deferred namespace reload.
The current exchange completes normally; only the outer runtime consumes the
request, reloads allowlisted project namespaces, invokes the rebuild metadata
on Integrant-assembled built-in plugins, and swaps the next exchange's chain.
Core engine/protocol namespace changes remain process-restart boundaries
because JVM protocol/class identity cannot be replaced safely in place.

## Extension points

- **New LLM provider:** implement `kschltz.agent.llm.client/LlmClient` (and optionally `llm.stream/StreamableLlmClient` for token/thinking SSE). See [`docs/stream.md`](stream.md). Add a case in `kschltz.agent.system/init-key :lateralus/llm-client`.
- **New tool:** build a namespace under `kschltz.agent.tools.*` that exports a `Tool` record (`deftype` or `defrecord`), add its registry to a new Integrant key (e.g. `:lateralus/web-tools`), and reference that key in `:lateralus/tool-registry`. Tool names use conservative snake_case (`^[A-Za-z][A-Za-z0-9_]{0,63}$`) so the same definitions work across OpenAI-compatible, Cerebras, Anthropic, Gemini, and Bedrock APIs; `tool-definition` rejects non-portable names before network I/O. Current examples: filesystem tools (`:lateralus/file-tools`), self-awareness tools (`:lateralus/self-awareness-tools`), session-config tools (`:lateralus/config-tools` — `set_llm_config`, `list_llm_models`, behind `ModelCatalog`; see [`docs/transitions.md`](transitions.md)), clojure structured-editing tools (`:lateralus/clojure-tools`), clojure runtime-eval tools (`:lateralus/runtime-tools` — `clojure_eval`, `clojure_add_lib`, `clojure_loaded_libs`, behind the `ClojureRuntime` protocol), web tools (`:lateralus/web-tools` with providers `:none`, `:mojeek`, and `:ddg`), and MCP client tools (`:lateralus/mcp-tools` — `McpSession` owning stdio/HTTP clients; control tools under `:lateralus/mcp-session-tools`; see [`docs/mcp.md`](mcp.md) and [`docs/dynamic-mcp-tool-setup.md`](dynamic-mcp-tool-setup.md)).
- **New memory backend:** implement `kschltz.agent.memory.protocol/MemoryBackend` and add a case in `kschltz.agent.system/init-key :lateralus/memory-backend`. Current implementations: noop (`noop-backend`), Proximum HNSW (`proximum-backend`), and KG + BM25 (`kg-bm25`).
- **New embedder:** implement `kschltz.agent.memory.embedding/Embedder` and add a case in `kschltz.agent.system/init-key :lateralus/embedder`. Current implementations: noop, HTTP (`http-embedding`), and LangChain4j in-process ONNX (`langchain4j-embedding`).
- **New plugin:** build a map `{:plugin/name ... :plugin/slots ...}` and add it to `:lateralus/plugins` in the Integrant config, or register a new plugin key and reference it from `:lateralus/plugins`.
- **New chain stage:** add an interceptor to an existing plugin slot or contribute a full `:plugin/chain`.
- **New tool:** implement `kschltz.agent.tool/Tool`, provide a registry helper, add an Integrant key in `kschltz.agent.system`, and reference it from `:lateralus/tool-registry`.

## Web tools

`kschltz.agent.tools.web` provides three network-capable `Tool`s:

- `web_search`
- `web_fetch`
- `web_extract`

They are exposed to the LLM through `:lateralus/tool-registry`, which is now a
vector of registry maps merged by `kschltz.agent.tools.web.web/merge-tool-registries`.

The default provider is `:none` (air-gapped). Live search is opt-in via `:provider :mojeek`
(Mojeek public HTML, parsed with `hickory`) or `:provider :ddg` (DuckDuckGo's
`html.duckduckgo.com/html` endpoint, reached with a browser TLS/HTTP2 fingerprint
via `impersonator-okhttp` so DDG returns real HTML instead of a CAPTCHA page).
Both `:mojeek` and `:ddg` are JVM-only and excluded from the GraalVM native-image
classpath; `resources/lateralus/native.edn` pins `:provider :none`. A Phase 3 guard
stack (SSRF resolve+pin, UA rotation, safe-redirect re-validation, duplicate-query
breaker, snippet hint) backs every live provider. See [`docs/web.md`](docs/web.md)
for configuration and security details.

## KG-BM25 memory backend

`kschltz.agent.memory.kg-bm25` is a pure-Clojure, embedding-free `MemoryBackend` intended as the native-image default. It requires no ONNX, no Panama Vector API, and no incubator JVM flags.

Storage layout (one directory per session):

```
sessions/kg-bm25/<session-id>/
  messages.edn    ; raw messages, one EDN map per line
  index.edn       ; inverted index + entity graph + derived stats
```

Hybrid recall:

- **Recent-N:** read messages, take last N by timestamp.
- **Top-Y:** BM25(query-text) fused via RRF with a knowledge-graph score derived from query entities.
- Results are merged, deduped by `:msg-id`, and sorted by `:timestamp`.

Configuration example:

```clojure
{:lateralus/memory-backend {:impl :kg-bm25
                            :store {:backend :file
                                    :path "sessions/kg-bm25"}
                            :top-y 3
                            :last-n 5
                            :rrf-k 60}}
```

Options:

- `:store` — `{:backend :file :path "..."}` or `{:backend :memory}`
- `:top-y` — number of top semantic matches (default 3)
- `:last-n` — number of recent messages (default 5)
- `:rrf-k` — RRF constant (default 60)
- `:extract-fn` — `(fn [content] #{entity ...})`, defaults to tokenization

## HTTP embedder

`kschltz.agent.memory.http-embedding` provides an `Embedder` that calls an OpenAI-compatible `/v1/embeddings` endpoint. It has no native dependencies and is therefore native-image-friendly (unlike the LangChain4j in-process ONNX embedder).

Configuration example:

```clojure
{:lateralus/embedder {:method :http
                      :base-url "http://localhost:11434/v1"
                      :model "nomic-embed-text"
                      :dimensions 768}}
```

Required keys: `:base-url`, `:model`, `:dimensions`. Optional: `:api-key`, `:connect-timeout-ms`, `:request-timeout-ms`.

## Config validation

`kschltz.agent.system` registers `defmethod ig/assert-key` for the externally-configurable `:lateralus/*` keys: `:lateralus/llm-client`, `:lateralus/embedder`, `:lateralus/memory-backend`, `:lateralus/web-tools`, `:lateralus/mcp-tools`, and `:lateralus/runtime-tools`. Each assertion uses a Malli schema and runs before any resources are allocated, so malformed configs fail fast with a clear explanation of which key is wrong and which fields are missing or invalid.

For example:

```clojure
(defmethod ig/assert-key :lateralus/llm-client [_ config]
  (assert-malli! :lateralus/llm-client LlmClientConfig config))
```

See `src/kschltz/agent/system.clj` for the current schemas.

## Single-threaded MVP

The runtime is intentionally synchronous for the MVP. `send-message` runs `chain/execute` on the caller thread. There is no worker thread and no message queue. A queue + worker is the right design only when there is a real consumer that needs non-blocking sends (a UI thread, a request handler, batch processing). Until then, the synchronous design removes the entire race-and-wakeup surface.

## Network boundaries

All external/network I/O is behind a protocol:

- LLM calls go through `LlmClient` (real HTTP implementation in `kschltz.agent.llm.http`).
- Embeddings go through `Embedder`.
- Memory storage/recall goes through `MemoryBackend`.

Implementation functions for network-bound protocols are instrumented with Malli schemas for input and output, per project rule.

Tool invocation (`kschltz.agent.tool/invoke-tool`) validates input/output
against each tool's Malli schema and returns model-visible envelopes on
failure: validation errors name the tool + phase + humanized key path
(`Tool '<name>' input/output validation failed: <path>`), and execution
throws return a JSON envelope `{:tool :phase :class :message :error}` so
the model can branch on the exception class without parsing prose. The
unavailable-tool marker (`Tool '<name>' is not available in this
session...`) is detected by the loop via that exact phrase, not the looser
`Tool '` prefix, so a validation error is never mistaken for a missing tool.

## Files of interest

| File | Responsibility |
|------|---------------|
| `src/kschltz/lateralus.clj` | `-main` entry point; delegates to CLI |
| `src/kschltz/agent/cli.clj` | argument parsing, Integrant init/halt, runtime invocation |
| `src/kschltz/agent/runtime.clj` | outer loop: ctx creation + chain call + state merge |
| `src/kschltz/agent/system.clj` | Integrant component definitions, default config, Malli `ig/assert-key` |
| `src/kschltz/agent/chain.clj` | interceptor engine |
| `src/kschltz/agent/interceptors.clj` | core interceptor stages |
| `src/kschltz/agent/interceptors/schema.clj` | Malli schemas for `Interceptor` and open `Ctx` |
| `src/kschltz/agent/plugin.clj` | plugin assembly (`assemble-chain`, `default-slot-order`, `Plugin` schema) |
| `src/kschltz/agent/tool.clj` | `Tool` protocol and registry helpers |
| `src/kschltz/agent/plugins/base.clj` | default base plugin with core chain slots |
| `src/kschltz/agent/plugins/memory.clj` | memory plugin (`:enrich` recall, `:persist` store) |
| `src/kschltz/agent/plugins/tools.clj` | tool plugin: seeds `:agent/tool-registry` |
| `src/kschltz/agent/plugins/summarizer.clj` | history-summarizer plugin (`:history-summarize` slot, overrides the summarizer `LlmClient`) |
| `src/kschltz/agent/loop.clj` | ReAct tool-calling loop interceptors |
| `src/kschltz/agent/loop/act.clj` | plan-then-yield detector + act-nudge (keep tools, continue the exchange) |
| `src/kschltz/agent/tools/filesystem.clj` | read-only filesystem `Tool` implementations |
| `src/kschltz/agent/tools/self.clj` | self-awareness tools (`self_status`, `runtime_describe` + playbook) |
| `src/kschltz/agent/tools/clojure.clj` | clojure structured-editing `Tool` implementations |
| `src/kschltz/agent/tools/runtime/protocol.clj` | `ClojureRuntime` protocol (runtime-eval boundary) |
| `src/kschltz/agent/tools/runtime/schemas.clj` | runtime-eval Malli schemas + config |
| `src/kschltz/agent/tools/runtime/jvm.clj` | in-process `ClojureRuntime` impl (eval + `add-libs`), Malli-instrumented |
| `src/kschltz/agent/tools/runtime/tools.clj` | `clojure_eval`, `clojure_add_lib`, `clojure_loaded_libs` Tool implementations and registry factory |
| `src/kschltz/agent/tools/web/protocol.clj` | `WebProvider` protocol |
| `src/kschltz/agent/tools/web/schemas.clj` | Web tool Malli schemas |
| `src/kschltz/agent/tools/web/guards.clj` | URL/query/snippet guard pipeline |
| `src/kschltz/agent/tools/web/ssrf.clj` | Phase 3 SSRF / UA / redirect guards |
| `src/kschltz/agent/tools/web/none.clj` | `:none` provider (air-gapped default) |
| `src/kschltz/agent/tools/web/mojeek.clj` | `:mojeek` live provider (JVM-only, opt-in) |
| `src/kschltz/agent/tools/web/ddg.clj` | `:ddg` live provider (JVM-only, opt-in; impersonator TLS fingerprint) |
| `src/kschltz/agent/tools/web/web.clj` | `web_search`, `web_fetch`, `web_extract` Tool implementations and registry factory |
| `src/kschltz/agent/tools/mcp/protocol.clj` | `McpClient` / `McpTransport` protocols |
| `src/kschltz/agent/tools/mcp/tools.clj` | MCP registry factory (stdio servers → adapted Tools) |
| `src/kschltz/agent/llm/client.clj` | `LlmClient` protocol + stub + HTTP wrapper |
| `src/kschltz/agent/llm/http.clj` | real OpenAI-shaped HTTP client |
| `src/kschltz/agent/llm/schemas.clj` | Malli schemas for LLM request/response shapes |
| `src/kschltz/agent/memory/protocol.clj` | `MemoryBackend` + `Embedder` protocols |
| `src/kschltz/agent/memory/embedding.clj` | noop `Embedder` |
| `src/kschltz/agent/memory/http_embedding.clj` | OpenAI-compatible HTTP `Embedder` |
| `src/kschltz/agent/memory/langchain4j_embedding.clj` | LangChain4j in-process ONNX `Embedder` |
| `src/kschltz/agent/memory/proximum_backend.clj` | Proximum HNSW `MemoryBackend` |
| `src/kschltz/agent/memory/kg_bm25.clj` | KG + BM25 `MemoryBackend` facade |
| `src/kschltz/agent/cli/spinner.clj` | CLI spinner / progress indicator |
| `src/kschltz/agent/memory/bm25.clj` | BM25 scoring |
| `src/kschltz/agent/memory/knowledge_graph.clj` | entity knowledge graph |
| `src/kschltz/agent/memory/store/file.clj` | file-backed session store |
| `src/kschltz/agent/memory/noop_backend.clj` | noop `MemoryBackend` |
| `resources/lateralus/config.edn` | runtime default config (Proximum + LangChain4j + file/self/clojure/web :none + empty mcp-tools) |
| `resources/lateralus/native.edn` | native-image config (KG-BM25 + noop embedder + file/self/clojure/runtime-eval with `:network? false` + web :none + empty mcp-tools) |
| `docs/mcp.md` | MCP client tools: stdio servers, naming, guards, e2e |

## End-to-end memory tests

A separate `^:e2e` test namespace (`test/kschltz/agent/e2e_memory_test.clj`) wires the real HTTP `LlmClient`, the LangChain4j in-process embedder, and the Proximum backend end-to-end. It defaults to a local Ollama instance (`http://localhost:11434/v1`, model `glm5.1:cloud`) and skips gracefully when Ollama or the model is unavailable. Use `LATERALUS_E2E_FAKE=true` for a deterministic fake-server mode that needs no external LLM.

Run it separately from the fast suite:

```bash
clojure -M:e2e
LATERALUS_E2E_FAKE=true clojure -M:e2e
```
