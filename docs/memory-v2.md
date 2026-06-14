# Memory v2 — Protocol Sketch

Fresh-start session storage for lateralus-v2. **No read/migration of v1 sessions.** **No Datalevin in MVP** — the `MemoryBackend` protocol is the contract, and the MVP ships a noop impl that satisfies it. A real persistent store (Datalevin, SQLite, LMDB, flat files, Proximum, etc.) is a follow-up that slots in as another implementation of the same protocol; no consumer changes required.

Conceptual reference: `docs/memory-system-mvi.md` (v1). v2 uses a new attribute namespace to avoid accidental v1 reads.

## Architecture (when a real backend lands)

Hybrid recall = **top-Y semantic** + **last-N recent**, deduped, sorted chronologically.

## Implemented backend: Proximum

`kschltz.agent.memory.proximum-backend` implements `MemoryBackend` using `org.replikativ/proximum`, a pure-JVM HNSW vector index. Message metadata is attached to each vector, so Proximum serves as both the vector store and the message store:

- **Vectors**: one per message, embedding the message `:content`.
- **Metadata**: `{:session-id :role :content :timestamp :msg-id}`.
- **Recent recall**: scan the index, filter by `:session-id`, sort by `:timestamp`, take last N.
- **Semantic recall**: embed the query, HNSW search, filter by `:session-id`, take top Y.
- **Hybrid**: merge both sets, dedupe by `:msg-id`, sort chronologically.

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

If `:store-config` is omitted, the backend defaults to in-memory storage and data is lost on close. Set `:sync-on-write? true` for durability on every exchange, or rely on `-close` (halt) to sync before shutdown.

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

Default sessions root: `./sessions/` (override via env/CLI in v2). The MVP noop backend does not read or write this directory.

## Datalevin-style schema (sketch for a future Datalevin backend; not used in MVP)

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

A real backend stores these on message entities as `:v2/msg-id` (and linked to session). The Proximum backend stores them as vector metadata. The MVP noop backend does not persist them.

## Embedding

| Profile | Default embedder | Notes |
|---------|------------------|-------|
| MVP | `noop` (returns `[0.0]`) | No embedding work in MVP |
| JVM dev (follow-up) | HTTP OpenAI-compatible `/v1/embeddings` | Works everywhere |
| JVM optional (follow-up) | ONNX in-process | Not in native-image profile |
| Native-image (follow-up) | HTTP only | Required for GraalVM stretch |

Implemented behind the `Embedder` protocol with Malli-instrumented I/O.

## MVP scope

- **`MemoryBackend` protocol**: `store-message`, `recall-hybrid`, `close`. Defined and tested in `kschltz.agent.memory.protocol`.
- **`noop` impl**: returns `[]` on recall, no-op on store/close. Default Integrant config.
- **`proximum` impl**: durable HNSW-backed memory. Activated by setting `:lateralus/memory-backend {:impl :proximum}`.
- **Memory plugin slots**: `:enrich` (recall injection) and `:persist` (exchange persistence) are wired in the plugin. With the noop backend they are no-ops; with Proximum they store and recall real session history.
- **No** `remember` tool facts in MVP (`:v2/kind` reserved for post-MVP).

## Verification

- `kschltz.agent.memory.proximum-backend-test` covers store/recall/session isolation/close.
- `kschltz.agent.memory-integration-test` verifies the memory plugin wiring through the Integrant system and runtime.
- See `goals/lateralus-v2-rewrite/plan.md` Step 6 for the original protocol/plugin acceptance criteria.
