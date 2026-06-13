# Memory v2 — Protocol Sketch

Fresh-start session storage for lateralus-v2. **No read/migration of v1 sessions.** **No Datalevin in MVP** — the `MemoryBackend` protocol is the contract, and the MVP ships a noop impl that satisfies it. A real persistent store (Datalevin, SQLite, LMDB, flat files, etc.) is a follow-up that slots in as another implementation of the same protocol; no consumer changes required.

Conceptual reference: `docs/memory-system-mvi.md` (v1). v2 uses a new attribute namespace to avoid accidental v1 reads.

## Architecture (when a real backend lands)

Hybrid recall = **top-Y semantic** + **last-N recent**, deduped, sorted chronologically.

## Storage layout (sketch for a future real store)

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

A real backend stores these on message entities as `:v2/msg-id` (and linked to session). The MVP noop backend does not persist them.

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
- **`noop` impl**: returns `[]` on recall, no-op on store/close. This is what the default Integrant config wires up.
- **Memory plugin slots**: `:enrich` (recall injection) and `:persist` (exchange persistence) are wired in the plugin, but with the noop backend they are no-ops. A real backend activates them.
- **No** `remember` tool facts in MVP (`:v2/kind` reserved for post-MVP).

## Verification

See `goals/lateralus-v2-rewrite/plan.md` Step 6 — protocol is well-formed, noop backend satisfies it, plugin slots exist and are no-op-safe.
