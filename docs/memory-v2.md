# Memory v2

Fresh-start session storage for lateralus-v2. **No read/migration of v1 sessions.** Session storage is implemented behind the `MemoryBackend` protocol; any number of storage backends can satisfy it. Three implementations ship:

- **`noop`** — test default; stores nothing, recalls `[]`.
- **`proximum`** — JVM runtime default; pure-JVM HNSW vector store + message metadata.
- **`kg-bm25`** — embedding-free, file-backed BM25 + knowledge-graph hybrid recall. Pure Clojure, no incubator flags, native-image-friendly.

Additional backend implementations (SQLite, LMDB, flat files, etc.) can be added as further implementations of the same protocol; no consumer changes required. Embedding-free options (BM25/keyword, knowledge-graph, episodic/procedural) are catalogued in [`docs/memory-embedding-free-alternatives.md`](memory-embedding-free-alternatives.md).

## Runtime default

`resources/lateralus/config.edn` selects an in-memory Proximum backend + LangChain4j in-process ONNX embedder by default:

```clojure
{:lateralus/embedder       {:method :langchain4j}
 :lateralus/memory-backend {:impl :proximum
                            :embedder #ig/ref :lateralus/embedder}}
```

For durable file-backed memory, add `:store`:

```clojure
{:lateralus/memory-backend {:impl :proximum
                            :embedder #ig/ref :lateralus/embedder
                            :store {:backend :file
                                    :path "sessions/proximum"
                                    :id #uuid "465df026-fcd3-4cb3-be44-29a929776250"}}}
```

The in-memory `system/default-config` keeps the noop backend + noop embedder so tests stay fast and isolated.

## Architecture

Hybrid recall = **top-Y semantic** + **last-N recent**, deduped, sorted chronologically.

## Implemented backend: Proximum

`kschltz.agent.memory.proximum-backend` implements `MemoryBackend` using `org.replikativ/proximum`, a pure-JVM HNSW vector index. Message metadata is attached to each vector, so Proximum serves as both the vector store and the message store:

- **Vectors**: one per message, embedding the message `:content`.
- **Metadata**: `{:session-id :role :content :timestamp :msg-id}`.
- **Recent recall**: scan the index, filter by `:session-id`, sort by `:timestamp`, take last N.
- **Semantic recall**: embed the query, HNSW search, filter by `:session-id`, take top Y.
- **Hybrid**: merge both sets, dedupe by `:msg-id`, sort chronologically.

The default embedder is `kschltz.agent.memory.langchain4j-embedding` (`:method :langchain4j`), which runs the bundled all-MiniLM-L6-v2 ONNX model in-process. It is JVM-only; native-image users must switch to an HTTP embedder.

## Implemented backend: KG + BM25

`kschltz.agent.memory.kg-bm25` is an embedding-free implementation:

- **Storage**: per-session `messages.edn` + `index.edn` under the configured `:path`.
- **Index**: inverted index for BM25 plus an entity-to-message knowledge graph built from tokenized message content.
- **Recall**: top-Y is the RRF fusion of BM25(query-text) and graph-entity(query-text); last-N is a timestamp scan.
- **Configuration**:

  ```clojure
  {:lateralus/memory-backend {:impl :kg-bm25
                              :store {:backend :file
                                      :path "sessions/kg-bm25"}
                              :top-y 5
                              :last-n 10
                              :rrf-k 60
                              :extract-fn my-custom-extractor}}
  ```

- **Native-image**: safe; no ONNX, no Panama Vector API, no native libraries.

A live transcription demo is in `dev/kg_bm25_transcription_demo.clj`:

```bash
clojure -M:dev -m kg-bm25-transcription-demo
```

It runs a scripted multi-turn session with the stub LLM and prints the
recall block injected into each LLM request, plus the final persisted
transcript.

## Storage layout

Proximum keeps its index under the configured `:store`. KG + BM25 stores one directory per session:

```
sessions/kg-bm25/<session-id>/
  messages.edn    ; raw messages, one EDN map per line
  index.edn       ; inverted index + graph + derived stats
```

Default sessions root: `./sessions/` (override via env/CLI in v2). The noop backend does not read or write this directory.

## Native-image configuration

The `resources/lateralus/native.edn` config is used by the GraalVM native-image binary. It avoids the JVM-only Proximum HNSW index and LangChain4j in-process ONNX embedder by wiring the KG + BM25 backend with an in-memory store and a noop embedder:

```clojure
{:lateralus/llm-client     {:impl :stub}
 :lateralus/llm-config     {}
 :lateralus/embedder       {:method :noop}
 :lateralus/memory-backend {:impl :kg-bm25
                            :store {:backend :memory}}
 :lateralus/memory-plugin  {:backend  #ig/ref :lateralus/memory-backend
                            :embedder #ig/ref :lateralus/embedder
                            :top-y    3
                            :last-n   5}
 :lateralus/file-tools     {}
 :lateralus/tool-registry  #ig/ref :lateralus/file-tools
 :lateralus/tools-plugin   {:registry #ig/ref :lateralus/tool-registry}
 :lateralus/plugins        [#ig/ref :lateralus/memory-plugin
                            #ig/ref :lateralus/tools-plugin]
 :lateralus/agent          {:plugins        #ig/ref :lateralus/plugins
                            :llm-client     #ig/ref :lateralus/llm-client
                            :llm-config     #ig/ref :lateralus/llm-config
                            :embedder       #ig/ref :lateralus/embedder
                            :memory-backend #ig/ref :lateralus/memory-backend}}
```

To enable dense embeddings in native-image, replace the noop embedder with the HTTP embedder:

```clojure
{:lateralus/embedder {:method :http
                      :base-url "http://localhost:11434/v1"
                      :model "nomic-embed-text"
                      :dimensions 768}}
```

## Traceability

Each exchange ctx carries:

- `:exchange/session-id` — CLI `-s` value or generated default
- `:exchange/user-msg-id` — UUID at exchange start
- `:exchange/assistant-msg-id` — UUID when response finalized

The Proximum backend stores these as vector metadata. The noop backend does not persist them.

## Embedding

Implemented behind the `Embedder` protocol.

| Method | Namespace | Model | Dimensions | Notes |
|--------|-----------|-------|------------|-------|
| `:noop` | `kschltz.agent.memory.embedding` | none | 1 | Test default; all vectors are `[0.0]` |
| `:langchain4j` | `kschltz.agent.memory.langchain4j-embedding` | all-MiniLM-L6-v2 (ONNX) | 384 | Runtime default; JVM only |
| `:http` | `kschltz.agent.memory.http-embedding` | OpenAI-compatible `/v1/embeddings` | configurable | Use for dense embeddings in GraalVM native-image (current native.edn default is `:noop`) |

The LangChain4j model weights are bundled in the jar, so no runtime network calls are needed. The first init extracts a native tokenizer library and loads the ONNX session.

## Scope

- **`MemoryBackend` protocol**: `store-message`, `recall-hybrid`, `close`. Defined and tested in `kschltz.agent.memory.protocol`.
- **`noop` impl**: returns `[]` on recall, no-op on store/close. In-memory test default.
- **`proximum` impl**: durable HNSW-backed memory. Runtime default in `resources/lateralus/config.edn`.
- **`:langchain4j` embedder**: in-process ONNX all-MiniLM-L6-v2. Runtime default.
- **Memory plugin slots**: `:enrich` (recall injection) and `:persist` (exchange persistence) are wired in the plugin. With the noop backend they are no-ops; with Proximum + LangChain4j they store and recall real session history.
- **History compaction**: a `:history-summarize` `:leave` interceptor runs after `store-exchange` and compacts long `:agent/history` into a single `[Conversation Summary - generated <ts>]` system message plus a protected window of the most-recent user turns. Trigger = 60 non-system messages; protected window = 10 user turns; cap (`max-history-entries`) = 100. See "History summarization policy" below.
- **No** `remember` tool facts in MVP (`:v2/kind` reserved for post-MVP).

## History summarization policy

`kschltz.agent.interceptors/summarize-history` (factored as
`kschltz.agent.plugins.summarizer/summarizer-plugin`) compresses a
long `:agent/history` so context growth stays bounded without silently
dropping the oldest turns.

| Constant | Value | Where |
|---|---|---|
| `max-history-entries` | 100 | hard cap on non-system messages retained in `:agent/history` after `trim-history` |
| `summarize-trigger` | 60 | non-system message count at which the summarizer fires |
| `protected-turn-pairs` | 10 | number of most-recent user turns preserved verbatim |

Behavior:

1. **Trigger guard.** When the non-system body grows past `summarize-trigger` (60), the interceptor reads the just-written `:agent/history` from `:agent/state-delta` and compacts.
2. **Split.** The body is split into `[oldest-block protected-window]` where the protected window covers the trailing 10 user turns and their following assistant/tool messages. The split anchors so the window never starts with a `:role "tool"` message (assistant tool_calls / tool pairs stay whole).
3. **Summarize.** The oldest block is sent to the configured `LlmClient` (or a stub/cheap model in production) with a dense "preserve user goals, decisions, facts, errors; drop pleasantries" instruction.
4. **Emit.** A single `:role "system"` message is prepended to the protected window, with content `"[Conversation Summary - generated <ms>]\n<summary>"`. The leading original system message (if any) stays at position 0.
5. **Write back.** The compacted history overwrites `:agent/history` in `:agent/state-delta`; the runtime merges it on the next exchange.

When no `LlmClient` is wired, the marker still emits with `[summary unavailable]` so the boundary is observable.

With the noop memory backend this has no effect on persisted memory — `:agent/history` is in-process state. With Proximum or kg-bm25, only the post-compaction summary is stored on the next persist call.

## Backend comparison

| Backend | Persistence | Embeddings | Native-image | Default config file | Test namespace |
|---------|-------------|------------|--------------|---------------------|----------------|
| `noop` | none | none | yes | in-memory test default | `kschltz.agent.memory.noop-backend-test` |
| `proximum` | in-memory or file-backed HNSW | dense (LangChain4j ONNX, 384-dim) | no | `resources/lateralus/config.edn` | `kschltz.agent.memory.proximum-backend-test` |
| `kg-bm25` | in-memory or file-backed EDN | none (BM25 + small KG) | yes | `resources/lateralus/native.edn` | `kschltz.agent.memory.kg-bm25-test` |

## Verification

- `kschltz.agent.memory.protocol-test` verifies the `MemoryBackend` and `Embedder` protocol contracts.
- `kschltz.agent.memory.noop-backend-test` covers the noop backend.
- `kschltz.agent.memory.langchain4j-embedding-test` verifies the in-process ONNX embedder.
- `kschltz.agent.memory.proximum-backend-test` covers store/recall/session isolation/close.
- `kschltz.agent.memory.kg-bm25-test` covers the embedding-free backend.
- `kschltz.agent.memory-integration-test` verifies the memory plugin wiring through the Integrant system and runtime.
- `kschltz.agent.e2e-memory-test` (run separately with `clojure -M:e2e`) exercises a real HTTP LLM + LangChain4j + Proximum end-to-end, defaulting to local Ollama `glm5.1:cloud`.
- See `goals/lateralus-v2-rewrite/plan.md` Step 6 for the original protocol/plugin acceptance criteria.
