# Workspace file index

Opt-in advisory index of workspace files and an append-only edit log.
The filesystem remains the source of truth. A stale SHA-256 still fails
`file_patch`. Index failures never fail a successful file mutation.

This is Option D from [`duckdb-core-engine.md`](./duckdb-core-engine.md).
The same store can also hold workbench sessions and historic stream turns
(Option C). The interceptor chain is unchanged.

## Components

| Integrant key | Role |
|---|---|
| `:lateralus/store` | `StoreEngine` — `:memory` (tests / air-gap) or `:duckdb` (JVM) |
| `:lateralus/file-index` | `FileIndex` façade over the store |
| `:lateralus/file-tools` | Pass `:file-index #ig/ref :lateralus/file-index` to hook tools |
| `:lateralus/session-store` | Opt-in `SessionStore` over `sessions` (else `catalog.edn`) |
| `:lateralus/stream-bus` | `{:impl :store :store …}` checkpoints closed turns |

Tables:

- `file_index` — path, sha256, size, mtime, extracted text (capped), indexed_at
- `file_edits` — path, tool, before/after SHA-256, optional line range, ts
- `sessions` — catalog row + EDN workspace payload (`turns` / `refs` / `agent-state`)
- `turns` / `events` — historic stream snapshots (live SSE stays in RAM)

DuckDB never auto-`INSTALL`s extensions. Search is regex over stored
content (same family as `file_search`), not the `fts` extension.

## Tools

When a FileIndex is wired, the filesystem registry also exposes:

- `file_reindex` — walk a path (containment + blocked-path + size caps) and upsert
- `file_edits` — list recent edit rows

`file_search` uses the index when that tree has at least one indexed
file; otherwise it still walks the disk. `file_write`, `file_update`,
`file_create`, and `file_patch` record a mutation after a verified commit.

## Config

CLI (stub LLM): `resources/lateralus/demo-file-index.edn`.

Workbench (CHAT | Portal): start the demo LLM, then the workbench profile.

```bash
clojure -M:dev -m file-index-demo-llm 18765
clojure -M:workbench:run -i --config resources/lateralus/demo-file-index-workbench.edn
```

The workbench workspace is `/tmp/lat-wb-index`. Durable file:

```clojure
{:lateralus/store {:impl :duckdb :path "sessions/lateralus.duckdb"}
 :lateralus/file-index {:store #ig/ref :lateralus/store}
 :lateralus/session-store {:store #ig/ref :lateralus/store}
 :lateralus/stream-bus {:impl :store :store #ig/ref :lateralus/store}
 :lateralus/file-tools {:workspace-root "."
                        :file-index #ig/ref :lateralus/file-index}
 :lateralus/workbench {:session-store #ig/ref :lateralus/session-store
                       :stream-bus #ig/ref :lateralus/stream-bus}}
```

Native-image stays on the walk-only file tools. `store/duckdb.clj` is
excluded from the filtered native classpath, same as Proximum.
