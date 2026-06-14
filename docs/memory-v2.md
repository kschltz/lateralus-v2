# Memory v2 — Protocol Sketch

Fresh-start session storage for lateralus-v2. **No read/migration of v1 sessions.** **No Datalevin in MVP** — the `MemoryBackend` protocol is the contract. Two implementations ship:

- **`noop`** — test default; stores nothing, recalls `[]`.
- **`proximum`** — runtime default; pure-JVM HNSW vector store + message metadata.

A non-Proximum backend (Datalevin, SQLite, LMDB, flat files, etc.) is a follow-up that slots in as another implementation of the same protocol; no consumer changes required.

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

### Requirements

- Java 22+.
- JVM flags:
  ```
  --add-modules=jdk.incubator.vector
  --enable-native-access=ALL-UNNAMED
  ```
  These flags are included in:
  - `deps.edn` `:test` alias
  - `build.clj` `test` and `uber` tasks
  - the `./target/lateralus-v2` launcher script

### Configuration

```clojure
:lateralus/memory-backend
{:impl :proximum
 :embedder #ig/ref :lateralus/embedder
 :store-config {:backend :file
                :path "sessions/proximum"
                :id #uuid "465df026-fcd3-4cb3-be44-29a929776250"}
 :dim 384            ; optional, defaults to embedder dimensions
 :capacity 10000     ; optional
 :M 16               ; optional
 :ef-construction 200 ; optional
 :ef-search 50       ; optional
 :distance :euclidean ; optional (or :cosine for normalized embeddings)
 :sync-on-write? false} ; optional; true syncs every store
```

If `:store-config` is omitted, the backend defaults to in-memory storage and data is lost on close. The runtime default config omits `:store-config` for a zero-setup in-memory experience; add `{:backend :file :path "..." :id #uuid "..."}` for durability. Set `:sync-on-write? true` for durability on every exchange, or rely on `-close` (halt) to sync before shutdown.

### In-memory example

```clojure
:lateralus/memory-backend
{:impl :proximum
 :embedder #ig/ref :lateralus/embedder
 :store-config {:backend :memory :id #uuid "..."}}
```

## Storage layout (sketch for a future Datalevin/SQLite store)

If a non-Proximum backend lands later, the sketch below still applies:

```
<LATERALUS_SESSIONS_DIR>/<session-id>/
  data.mdb          ; entities (Datalog, LMDB, or whatever the store uses)
  vectors/          ; HNSW vector index (separate from entities)
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
