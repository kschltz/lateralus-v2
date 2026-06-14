# Memory v2 — Protocol Sketch

Fresh-start session storage for lateralus-v2. **No read/migration of v1 sessions.** **No Datalevin in MVP** — the `MemoryBackend` protocol is the contract. Three implementations ship:

- **`noop`** — test default; stores nothing, recalls `[]`.
- **`proximum`** — runtime default; pure-JVM HNSW vector store + message metadata.
- **`kg-bm25`** — embedding-free, file-backed BM25 + knowledge-graph hybrid recall. Pure Clojure, no incubator flags, native-image-friendly.

A non-Proximum backend (Datalevin, SQLite, LMDB, flat files, etc.) is a follow-up that slots in as another implementation of the same protocol; no consumer changes required. Embedding-free options (BM25/keyword, knowledge-graph, episodic/procedural) are catalogued in `docs/memory-embedding-free-alternatives.md`.

Conceptual reference: `docs/memory-system-mvi.md` (v1). v2 uses a new attribute namespace to avoid accidental v1 reads.

## Runtime default

`resources/lateralus/config.edn` selects Proximum + LangChain4j in-process ONNX embedding by default:

```clojure
{:lateralus/embedder       {:method :langchain4j}
 :lateralus/memory-backend {:impl :proximum
                            :embedder #ig/ref :lateralus/embedder}}
```

This gives real session memory (recent + semantic recall) out of the box when running via the CLI or the uberjar launcher. The in-memory `system/default-config` keeps the noop backend + noop embedder so tests stay fast and isolated.

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

`kschltz.agent.memory.kg-bm25-backend` is an embedding-free implementation:

- **Storage**: per-session `messages.edn` + `index.edn` under the configured `:path`.
- **Index**: inverted index for BM25 plus an entity-to-message knowledge graph built from tokenized message content.
- **Recall**: top-Y is the RRF fusion of BM25(query-text) and graph-entity(query-text); last-N is a timestamp scan.
- **Configuration**:

  ```clojure
  {:lateralus/memory-backend {:impl :kg-bm25
                              :store {:backend :file
                                      :path "sessions/kg-bm25"}
                              :top-y 5
                              :last-n 10}}
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

Proximum keeps its index under the configured `:store-config`. KG + BM25 stores one directory per session:

```
sessions/kg-bm25/<session-id>/
  messages.edn    ; raw messages, one EDN map per line
  index.edn       ; inverted index + graph + derived stats
```

Default sessions root: `./sessions/` (override via env/CLI in v2). The noop backend does not read or write this directory.

## Datalevin-style schema (sketch for a future Datalevin backend; not used)

```clojure
{:v2/session-id   {:db/valueType :db.type/string :db/unique :db.unique/identity}
 :v2/model        {:db/valueType :db.type/string}
 :v2/emb-method   {:db/valueType :db.type/string}  ;; "http" | "onnx" (JVM only)
 :v2/emb-model    {:db/valueType :db.type/string}
 :v2/msg-id       {:db/valueType :db.type/string :db/unique :db.unique/identity}
 :v2/session      {:db/valueType :db.type/string}
 :v2/role         {:db/valueType :db.type/string}   ;; "user" | "assistant" | "tool"
 :v2/text         {:db/valueType :db.type/string}
 :v2/timestamp    {:db/valueType :db.type/long}
 :v2/indexed      {:db/valueType :db.type/boolean}  ;; false until vector write succeeds
 :v2/tool-name    {:db/valueType :db.type/string}
 :v2/tool-result  {:db/valueType :db.type/string}
 :v2/tool-calls   {:db/valueType :db.type/string}   ;; JSON
 :v2/tool-call-id {:db/valueType :db.type/string}}
```

Vectors keyed by `:v2/msg-id` in a separate LMDB store (when a Datalevin backend lands).

`:v2/indexed` — `false` at entity commit, `true` after successful vector index write. Startup `reindex-pending!` retries orphaned messages.

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
| `:http` (follow-up) | — | OpenAI-compatible `/v1/embeddings` | configurable | Required for GraalVM native-image |

The LangChain4j model weights are bundled in the jar, so no runtime network calls are needed. The first init extracts a native tokenizer library and loads the ONNX session.

## Scope

- **`MemoryBackend` protocol**: `store-message`, `recall-hybrid`, `close`. Defined and tested in `kschltz.agent.memory.protocol`.
- **`noop` impl**: returns `[]` on recall, no-op on store/close. In-memory test default.
- **`proximum` impl**: durable HNSW-backed memory. Runtime default in `resources/lateralus/config.edn`.
- **`:langchain4j` embedder**: in-process ONNX all-MiniLM-L6-v2. Runtime default.
- **Memory plugin slots**: `:enrich` (recall injection) and `:persist` (exchange persistence) are wired in the plugin. With the noop backend they are no-ops; with Proximum + LangChain4j they store and recall real session history.
- **No** `remember` tool facts in MVP (`:v2/kind` reserved for post-MVP).

## Verification

- `kschltz.agent.memory.langchain4j-embedding-test` verifies the in-process ONNX embedder.
- `kschltz.agent.memory.proximum-backend-test` covers store/recall/session isolation/close.
- `kschltz.agent.memory-integration-test` verifies the memory plugin wiring through the Integrant system and runtime.
- `kschltz.agent.e2e-memory-test` (run separately with `clojure -M:e2e`) exercises a real HTTP LLM + LangChain4j + Proximum end-to-end, defaulting to local Ollama `glm5.1:cloud`.
- See `goals/lateralus-v2-rewrite/plan.md` Step 6 for the original protocol/plugin acceptance criteria.
