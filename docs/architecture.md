# Lateralus v2 Architecture

This document describes the current Integrant + plugin/interceptor architecture of `lateralus-v2`. For the original thesis and historical context, see [`docs/interceptor-loop-design-note.md`](docs/interceptor-loop-design-note.md). For the memory subsystem, see [`docs/memory-v2.md`](docs/memory-v2.md).

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
│  :lateralus/web-search-tools  ──▶  web search tool registry (DuckDuckGo Lite / SearXNG)│
│  :lateralus/tool-registry     ──▶  map of tool name -> Tool impl      │
│  :lateralus/tools-plugin      ──▶  seeds `:agent/tool-registry`        │
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
│  :tools    → dispatch-tools-interceptor, compose-tool-results-interceptor │
│  :finalize → tool-loop-interceptor                                │
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
 :history :persist :observe :notify]
```

| Slot | Phase | Typical use | Wired by |
|------|-------|-------------|----------|
| `:guard` | enter | safety / safety checks before compose | base plugin (`error-boundary`) |
| `:enrich` | enter | RAG / memory recall before compose | memory plugin |
| `:compose` | enter | build `:llm/request` from state + recall + user text | base plugin (`compose-context`) |
| `:llm` | enter | call the LLM, parse response | base plugin (`llm-call-with-self-heal`, `llm-call`, `parse-response`) |
| `:dispatch` | enter | reserved slot (no interceptor wired) | — |
| `:tools` | enter | dispatch and run registered tools | base plugin (`dispatch-tools-interceptor`, `compose-tool-results-interceptor`) |
| `:finalize` | enter | tool loop termination / post-tool | base plugin (`tool-loop-interceptor`) |
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

The `Ctx` Malli schema in `kschltz.agent.interceptors.schema` is intentionally open: it validates only a few instrumentation and traceability keys, leaving domain keys free for plugins to extend without a schema migration.

## State and state-delta

Only the outer runtime loop holds a mutable reference — an atom seeded with `:initial-state` from the agent-map. Interceptors never mutate shared refs; instead they emit `:agent/state-delta`. The runtime merges this delta into the atom using `kschltz.agent.runtime/merge-state`, which performs a deep merge for known nested keys (e.g. `:agent/state`) and last-write-wins for top-level keys.

## Extension points

- **New LLM provider:** implement `kschltz.agent.llm.client/LlmClient` and add a case in `kschltz.agent.system/init-key :lateralus/llm-client`.
- **New memory backend:** implement `kschltz.agent.memory.protocol/MemoryBackend` and add a case in `kschltz.agent.system/init-key :lateralus/memory-backend`. Current implementations: noop (`noop-backend`), Proximum HNSW (`proximum-backend`), and KG + BM25 (`kg-bm25`).
- **New embedder:** implement `kschltz.agent.memory.protocol/Embedder` and add a case in `kschltz.agent.system/init-key :lateralus/embedder`. Current implementations: noop, HTTP (`http-embedding`), and LangChain4j in-process ONNX (`langchain4j-embedding`).
- **New plugin:** build a map `{:plugin/name ... :plugin/slots ...}` and add it to `:lateralus/plugins` in the Integrant config, or register a new plugin key and reference it from `:lateralus/plugins`.
- **New chain stage:** add an interceptor to an existing plugin slot or contribute a full `:plugin/chain`.
- **New web search provider:** implement `kschltz.agent.tools.web-search.protocol/WebSearchProvider` and add a case in `kschltz.agent.tools.web-search/select-provider`.
- **New tool:** implement `kschltz.agent.tool/Tool`, provide a registry helper, add an Integrant key in `kschltz.agent.system`, and reference it from `:lateralus/tool-registry`.

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

`kschltz.agent.system` registers `defmethod ig/assert-key` for the three externally-configurable `:lateralus/*` keys (`:lateralus/llm-client`, `:lateralus/embedder`, and `:lateralus/memory-backend`). Each assertion uses a Malli schema and runs before any resources are allocated, so malformed configs fail fast with a clear explanation of which key is wrong and which fields are missing or invalid.

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
| `src/kschltz/agent/plugins/base.clj` | default base plugin with core chain slots |
| `src/kschltz/agent/plugins/memory.clj` | memory plugin (`:enrich` recall, `:persist` store) |
| `src/kschltz/agent/plugins/tools.clj` | tool plugin: seeds `:agent/tool-registry` |
| `src/kschltz/agent/loop.clj` | ReAct tool-calling loop interceptors |
| `src/kschltz/agent/tool.clj` | `Tool` protocol and registry helpers |
| `src/kschltz/agent/tools/filesystem.clj` | read-only filesystem `Tool` implementations |
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
| `resources/lateralus/config.edn` | runtime default config (Proximum + LangChain4j + file-tools) |
| `resources/lateralus/native.edn` | native-image config (KG-BM25 + noop embedder + file-tools) |

## End-to-end memory tests

A separate `^:e2e` test namespace (`test/kschltz/agent/e2e_memory_test.clj`) wires the real HTTP `LlmClient`, the LangChain4j in-process embedder, and the Proximum backend end-to-end. It defaults to a local Ollama instance (`http://localhost:11434/v1`, model `glm5.1:cloud`) and skips gracefully when Ollama or the model is unavailable. Use `LATERALUS_E2E_FAKE=true` for a deterministic fake-server mode that needs no external LLM.

Run it separately from the fast suite:

```bash
clojure -M:e2e
LATERALUS_E2E_FAKE=true clojure -M:e2e
```
