# Memory v2 — Schema Sketch

Fresh-start session storage for lateralus-v2. **No read/migration of v1 sessions.**

Conceptual reference: `docs/memory-system-mvi.md` (v1). v2 uses new attribute namespace to avoid accidental v1 reads.

## Architecture

Hybrid recall = **top-Y semantic** + **last-N recent**, deduped, sorted chronologically.

## Storage layout

```
<LATERALUS_SESSIONS_DIR>/<session-id>/
  data.mdb          ; Datalog entities
  vectors/          ; HNSW vector index (separate from entities)
```

Default sessions root: `./sessions/` (override via env/CLI in v2).

## Datalevin schema (v2)

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

Vectors keyed by `:v2/msg-id` in separate LMDB store (not on entities).

`:v2/indexed` — `false` at Datalog commit, `true` after successful vector index write. Startup `reindex-pending!` retries orphaned messages.

## Traceability

Each exchange ctx carries:

- `:exchange/session-id` — CLI `-s` value or generated default
- `:exchange/user-msg-id` — UUID at exchange start
- `:exchange/assistant-msg-id` — UUID when response finalized

Stored on message entities as `:v2/msg-id` (and linked to session).

## Embedding

| Profile | Default embedder | Notes |
|---------|------------------|-------|
| JVM dev | HTTP OpenAI-compatible `/v1/embeddings` | Works everywhere |
| JVM optional | ONNX in-process | Not in native-image profile |
| Native-image | HTTP only | Required for GraalVM stretch |

Implement behind `Embedder` protocol with Malli-instrumented I/O.

## MVP scope

- Store/recall chat messages across turns
- Hybrid recall injection pre-compose (memory plugin `:enrich` slot)
- Persist exchange on leave (memory plugin `:persist` slot)
- **No** `remember` tool facts in MVP (`:v2/kind` reserved for post-MVP)

## Verification

See `goals/lateralus-v2-rewrite/plan.md` Step 6 — memory integration test recalls prior turn in same session.
