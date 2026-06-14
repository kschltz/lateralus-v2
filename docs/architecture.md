# Lateralus v2 Architecture

This document describes the runtime architecture of `lateralus-v2`. For the original thesis, see [`interceptor-loop-implementation-plan.md`](interceptor-loop-implementation-plan.md). For the memory schema, see [`memory-v2.md`](memory-v2.md).

## Overview

Lateralus v2 is a single-user LLM agent built around three ideas:

1. **Interceptor chain engine** — every stage of an exchange (context composition, LLM call, response parsing, dispatch, persistence, delivery) is a pure interceptor that receives and returns an immutable context map.
2. **Integrant lifecycle** — all extensible components (LLM client, embedder, memory backend, plugins) are Integrant keys. The system is started with `ig/init` and shut down with `ig/halt!`.
3. **Thin outer loop** — the runtime creates a per-exchange context, runs the chain, and merges `:agent/state-delta` into an in-memory atom. The CLI, tests, and any future web server all call this same runtime.

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
│  :lateralus/llm-client  ──▶  LlmClient protocol impl (stub/http)  │
│  :lateralus/llm-config  ──▶  raw opts (base-url/api-key/model)  │
│  :lateralus/embedder    ──▶  Embedder protocol impl (noop)        │
│  :lateralus/memory-backend ──▶ MemoryBackend protocol (noop/proximum) │
│  :lateralus/plugins     ──▶  plugin maps (base + memory)            │
│  :lateralus/agent       ──▶  agent-map + exchange-chain         │
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
│  execute(ctx, [error-boundary                                    │
│               bind-llm-client                                   │
│               compose-context    ← builds LLM request             │
│               llm-call           ← calls LlmClient               │
│               parse-response     ← extracts text/tool_calls      │
│               dispatch           ← records (empty) tool results  │
│               store-exchange     ← persists on :leave          │
│               deliver-responses  ← queues on :leave              │
│               notify])           ← final hook on :leave         │
└─────────────────────────────────────────────────────────────────┘
```

## The context map

The context is an open map. Engine state (`::chain/queue`, `::chain/stack`, `::chain/error`) is namespaced so it never collides with domain keys. Domain keys of interest:

| Key | Set by | Read by | Meaning |
|-----|--------|---------|---------|
| `:exchange/session-id` | runtime | all stages | stable session identifier |
| `:exchange/user-msg-id` | runtime | all stages | UUID for this user turn |
| `:exchange/assistant-msg-id` | runtime | all stages | UUID for the assistant response |
| `:exchange/user-text` | runtime | compose-context | the user's prompt |
| `:agent/state` | runtime | compose-context | persistent state (LLM config, system message, history) |
| `:agent/llm-client` | runtime | bind-llm-client | Integrant-configured LlmClient |
| `:llm/client` | bind-llm-client | llm-call | the client to invoke |
| `:llm/request` | compose-context | llm-call | OpenAI-shaped request body |
| `:llm/response` | llm-call | parse-response | raw provider response |
| `:exchange/response` | parse-response | deliver-responses | final assistant text |
| `:agent/state-delta` | any stage | runtime | state changes to merge after the exchange |

## State and state-delta

Only the outer runtime loop holds a mutable reference — an atom seeded with `:initial-state` from the agent-map. Interceptors never mutate shared refs; instead they emit `:agent/state-delta`. The runtime merges this delta into the atom with plain `merge` (last-write-wins per key). If a stage needs deep-merge semantics for a particular nested key, it should compute the new value itself and emit it already-merged.

## Extension points

- **New LLM provider:** implement `kschltz.agent.llm.client/LlmClient` and add a case in `kschltz.agent.system/init-key :lateralus/llm-client`.
- **New memory backend:** implement `kschltz.agent.memory.protocol/MemoryBackend` and add a case in `kschltz.agent.system/init-key :lateralus/memory-backend`. The current implementation includes a noop backend and a Proximum HNSW backend (`kschltz.agent.memory.proximum-backend`).
- **New embedder:** implement `kschltz.agent.memory.embedding/Embedder` and add a case in `kschltz.agent.system/init-key :lateralus/embedder`.
- **New plugin:** build a map `{:plugin/name ... :plugin/slots ...}` and add it to `:lateralus/plugins` in the Integrant config.
- **New chain stage:** build an interceptor map and add it to `default-exchange-chain` in `kschltz.agent.exchange` (or assemble it via a plugin).

## Single-threaded MVP

The runtime is intentionally synchronous for the MVP. `send-message` runs `chain/execute` on the caller thread. There is no worker thread and no message queue. A queue + worker is the right design only when there is a real consumer that needs non-blocking sends (a UI thread, a request handler, batch processing). Until then, the synchronous design removes the entire race-and-wakeup surface.

## Network boundaries

All external/network I/O is behind a protocol:
- LLM calls go through `LlmClient` (the real HTTP implementation is in `kschltz.agent.llm.http`).
- Embeddings go through `Embedder`.
- Memory storage/recall goes through `MemoryBackend`.

No `http/completion` calls exist outside `llm/http.clj`. No `kschltz.agent.loop` dependency exists in the interceptor namespace.

## Files of interest

| File | Responsibility |
|------|---------------|
| `src/kschltz/lateralus.clj` | `-main` entry point; delegates to CLI |
| `src/kschltz/agent/cli.clj` | argument parsing, Integrant init/halt, runtime invocation |
| `src/kschltz/agent/runtime.clj` | outer loop: ctx creation + chain call + state merge |
| `src/kschltz/agent/system.clj` | Integrant component definitions and default config |
| `src/kschltz/agent/chain.clj` | interceptor engine |
| `src/kschltz/agent/exchange.clj` | default exchange-chain assembly |
| `src/kschltz/agent/interceptors.clj` | core interceptor stages |
| `src/kschltz/agent/plugin.clj` | plugin assembly |
| `src/kschltz/agent/llm/client.clj` | `LlmClient` protocol + stub + HTTP wrapper |
| `src/kschltz/agent/llm/http.clj` | real OpenAI-shaped HTTP client |
| `src/kschltz/agent/memory/protocol.clj` | `MemoryBackend` protocol |
| `src/kschltz/agent/memory/embedding.clj` | `Embedder` protocol + noop impl |
| `src/kschltz/agent/memory/proximum_backend.clj` | Proximum HNSW `MemoryBackend` impl |
| `src/kschltz/agent/memory/noop_backend.clj` | noop `MemoryBackend` impl |
