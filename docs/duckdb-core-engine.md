# DuckDB as a Lateralus core store — options

**Status:** shipped — Option D (workspace index + edit log) and Option C
session/stream façades on the same `StoreEngine`.
**Date:** 2026-09-04
**Decision:** The harness payoff is a file index, an edit audit log, and
(opt-in) a session catalog plus historic stream checkpoints in one store.
Filesystem bytes stay source of truth; a `StoreEngine` (memory for tests,
DuckDB on JVM) holds `file_index`, `file_edits`, `sessions`, `turns`, and
`events`. Mutations record witnesses after a successful commit. `file_search`
may hit the index when it has coverage; otherwise it still walks. Live SSE
stays in-memory (64-turn cap); DuckDB is the checkpoint on `-close-turn!`.
The default `SessionStore` remains the file catalog
(`sessions/workbench/catalog.edn`). Secrets, the interceptor chain,
native-image defaults, and Proximum stay unchanged. MemoryBackend-on-store
and `store_query` (Option E) stay deferred.

This note answers: *what would it mean to put DuckDB at the center of the
Lateralus harness, and which of those meanings are actually a good fit?*

It is a companion to [`memory-backend-research.md`](./memory-backend-research.md)
(which already reserved a “SQL-backed backend” slot for SQLite + sqlite-vec)
and [`architecture.md`](./architecture.md) (locked interceptor + protocol
shape). It does **not** propose replacing the interceptor chain.

## Verdict in one paragraph

DuckDB is a strong candidate as a **single-process durable query substrate**
behind the existing protocols (`MemoryBackend`, `SessionStore`, `StreamBus`,
maybe a workspace index). It is a poor candidate as a **control-plane
replacement** for `kschltz.agent.chain` / `runtime`. The locked architecture
already has a center: an immutable context map flowing through interceptors.
DuckDB should sit where Konserve, `catalog.edn`, in-memory atoms, and the
KG-BM25 EDN files sit today — one file the JVM opens, protocols write, and
(later) the model can query through a guarded tool. That is “center of
persistence,” not “center of the engine.”

The recommended first cut is **Option C** below: one Integrant-owned DuckDB
connection, protocol implementations as thin tables, FTS/BM25 for recall,
secrets and live control flow left where they are.

## What is already the “engine”

Lateralus v2 is built around three locked ideas ([`AGENT_INSTRUCTIONS.md`](../AGENT_INSTRUCTIONS.md)):

1. **Interceptor chain** on an immutable context map.
2. **Integrant lifecycles** for every durable/network component.
3. **Thin outer runtime** that is the only thing allowed to merge
   `:agent/state-delta` into an atom.

External I/O is **protocol + Malli-instrumented**. There is no Datalevin.
Native-image exists and currently **excludes** Proximum / LangChain4j / live
web, defaulting to KG + BM25.

DuckDB does not replace any of that. If it “becomes the engine,” we have
broken the rewrite’s one rule.

## What is actually fragmented today

The harness already has several independent stores. That fragmentation is the
real problem DuckDB could solve.

| Surface | Today | Durable? | Queryable? |
|---------|-------|----------|------------|
| Runtime session state (`:agent/state` atom) | In-process map; deep-merged after each exchange | Only if a `SessionStore` snapshot is taken | No |
| Chat memory | `MemoryBackend`: Proximum+Konserve **or** KG-BM25 EDN files **or** noop | Yes (when configured) | Hybrid recall only, not SQL |
| Workbench session catalog | `SessionStore` → `sessions/workbench/catalog.edn` | Yes (whole-catalog rewrite) | List/get by id |
| Stream / turn events | In-memory `StreamBus`, **64-turn cap**, evicted | No | Snapshot + SSE only |
| Workflow artifacts | In-process atom (`WorkflowEngine`) | Copied onto `:agent/runtime-tools` in state-delta | Status map only |
| MCP / factory overlays | Atoms + state-delta maps | Session snapshot only | No |
| File harness | Live filesystem; `file_search` is a bounded regex walk | Files themselves | No index |
| Secrets | Sealed `LATSEC1` file (`SecretStore`) | Yes | Labels only — **must stay out of any shared DB** |
| Skills | On-disk `.edn` packs | Yes | Catalog fragment + path gating |

The workbench, memory plugin, stream plugin, and file tools do not share a
schema. A turn that is visible in CHAT can vanish from the stream bus after
64 turns, while the same session’s messages live in a different backend, and
the session catalog is a third file. That is the gap a “core store” would
close.

## Why DuckDB is a plausible fit

DuckDB is an **embedded, in-process, single-file** analytical database with a
JDBC driver (`org.duckdb:duckdb_jdbc`). For this repo the attractive
properties are:

- **Process model matches the MVP.** The runtime is single-threaded
  (`send-message` runs the chain on the caller thread). DuckDB’s concurrency
  story is “one writer process, many reader threads, optimistic MVCC.” We do
  not need multi-process writers. Workbench HTTP + the runtime already share
  one JVM; a single connection (or a tiny pool with serialized writes) is
  enough.
- **SQL + JSON in one place.** Messages, turns, tool traces, workflow
  artifacts, and file metadata are semi-structured. DuckDB’s JSON type and
  `read_json` / `json_extract` let us keep EDN/JSON blobs *and* project
  columns we want to filter on.
- **BM25 is a first-class extension.** `INSTALL fts; LOAD fts;` then
  `PRAGMA create_fts_index(...)` and `match_bm25`. That is the same retrieval
  family as `:kg-bm25`, without maintaining our own inverted index.
- **Optional dense recall.** The experimental `vss` extension adds HNSW over
  `FLOAT` `ARRAY` columns (`l2sq` / `cosine` / `inner_product`). That could
  sit next to LangChain4j embeddings the way Proximum does today — JVM-only,
  and persistence of the HNSW index is still experimental
  (`hnsw_enable_experimental_persistence`).
- **Analytics the current stores cannot do.** “Token usage by model this
  week,” “which tools failed after `file_patch`,” “recall hit-rate vs last-N”
  are ordinary SQL over an events table. Portal already wants tabular data.
- **Native-image is no longer a hard no.** Recent `duckdb_jdbc` ships GraalVM
  JNI reachability metadata. Official docs require GraalVM for JDK 22+
  (matches this project) and `--enable-native-access=ALL-UNNAMED` (already
  on `:run` / `:native`). The remaining cost is **shipping the per-platform
  native library** and **not auto-downloading extensions** in air-gapped /
  native builds.

## Why it is not a free lunch

- **Native C++ engine inside the JAR.** Same class of problem that killed
  Datalevin for MVP (JNI / LMDB). DuckDB’s Graal story is better documented
  than Datalevin’s, but it is still a native library extracted at runtime.
  Native-image must stay on KG-BM25 until a Lateralus binary actually boots
  with DuckDB.
- **Extensions vs air-gap.** `fts` and `vss` are not always statically
  linked. `INSTALL` talks to the DuckDB extension repo. Default configs that
  expect no network (`demo-stub.edn`, `:none` web, native.edn) must
  `LOAD` a vendored `.duckdb_extension` or degrade to SQL-only / Clojure
  BM25. Autoload-from-the-internet is incompatible with the air-gapped
  default.
- **VSS persistence is experimental.** Do not make HNSW-on-DuckDB the JVM
  default while Proximum already works. Use VSS as an optional column, or
  keep Proximum as the vector index and DuckDB as the row store.
- **OLAP, not OLTP.** Fine for append-mostly event/message/file-index
  writes. A poor fit for high-churn point updates (per-token stream appends
  *can* work if we batch, or if we keep the live turn in the atom and
  checkpoint on close).
- **Single writer process.** A second Lateralus process opening the same
  `.duckdb` file for write will fail. Session catalogs today are
  whole-file EDN rewrites with the same limitation in practice; document it.
- **Secrets.** Ciphertext and passphrases must not live in the shared
  database. `SecretStore` stays a sealed file.
- **Clojure interop is JDBC, not EDN-native.** We will want a thin
  `StoreEngine` (or `DuckDb`) protocol that runs parameterized SQL and
  returns Clojure maps. Interceptors and tools never see `java.sql.*`.
  That protocol is a **local I/O boundary** (like `SessionStore`), not a
  network boundary — still Malli-instrument the leaf.

## Options

Five shapes, from “another backend” to “the world model.” They compose;
nothing requires jumping to E.

### A — DuckDB as a `MemoryBackend` only

**What.** `:impl :duckdb` next to `:proximum` and `:kg-bm25`. Tables for
`messages` (+ optional `entities` / `edges` if we keep the KG channel).
Recall = `match_bm25` top-Y ∪ last-N, RRF optional. Embeddings ignored, or
stored for a later VSS pass.

**Fits.** Existing Integrant key, existing plugin, existing tests as a new
backend suite. Closest to the “SQL-backed backend” row already in
[`memory-backend-research.md`](./memory-backend-research.md).

**Does not do.** Session catalog, stream durability, file index, workflow
store. Does not make DuckDB “the center.”

**When to pick this.** We only want better durable recall and are unwilling
to touch workbench/stream/workflow.

### B — DuckDB as workbench durability (sessions + streams)

**What.** `SessionStore` and historic `StreamBus` share one file. Catalog
rows instead of rewriting `catalog.edn`. Turns/events survive past the
64-turn in-memory cap. Live SSE can still be the in-memory bus; DuckDB is
the checkpoint on `-close-turn!` (and optionally batched deltas).

**Fits.** Workbench Sessions UI and `/api/turns/:id` become actually
durable. Portal can query usage/tool traces.

**Does not do.** Semantic memory. File harness stays a regex walk.

**When to pick this.** The pain is “I restarted the workbench and lost
turns / the catalog is a blob,” not “recall is weak.”

### C — Shared `StoreEngine`, many protocol façades  ← recommended

**What.** One Integrant key, e.g. `:lateralus/store`:

```
:lateralus/store
  {:impl :duckdb          ; or :memory for tests
   :path "sessions/lateralus.duckdb"
   :extensions #{:json}   ; :fts opt-in; never auto-INSTALL
   :read-only? false}

:lateralus/memory-backend  {:impl :store :store #ig/ref :lateralus/store ...}
:lateralus/session-store   {:impl :store :store #ig/ref :lateralus/store}
:lateralus/stream-bus      {:impl :store :store #ig/ref :lateralus/store
                            :live :memory}   ; live atom + durable historic
```

A small `StoreEngine` protocol owns connection lifecycle, parameterized
query/execute, and halt. `MemoryBackend`, `SessionStore`, `StreamBus`, and
(later) `WorkflowEngine` persistence are **façades** over tables:

```
sessions
messages          session_id, msg_id, role, content, ts, embedding?
entities / edges  optional KG channel (port of kg-bm25, not a rewrite)
turns / events    stream historic
tool_calls        name, args_hash, latency, status  (from stream events)
workflow_actions / artifacts
file_index / file_edits     (Option D)
```

**Fits the locked architecture.** Interceptors still do not open JDBC.
Network rules unchanged. Secrets/skills stay on their own protocols.
Tests keep a `:memory` DuckDB (or a fake `StoreEngine`) so the fast suite
does not need a native lib if we so choose.

**Makes DuckDB “the center” in the only sense that is safe:** every durable
harness fact is in one file, one schema, one query language — while the
*engine* remains the chain.

**When to pick this.** We want one persistence story for the harness, not a
fourth ad-hoc EDN format.

### D — Workspace index (file harness)

**What.** On top of C, maintain `file_index` (path, sha256, size, mtime,
maybe extracted text) and `file_edits` (witness-in, witness-out, tool,
range). `file_search` can hit FTS instead of walking the tree; mutations
already compute SHA-256 witnesses — those become rows. The filesystem
remains the source of truth for bytes; DuckDB is the **index and audit log**.

**Fits.** Coding-agent harness: “what did we change this session,” “find
usages without regex-scanning 128 KB caps,” Portal timelines of edits.

**Must not do.** Bypass containment, blocked paths, per-path locks, or
atomic landing. The index is advisory; a stale hash still fails
`file_patch`. Re-index is a tool or a plugin `:persist` hook, not a
background daemon (MVP is single-threaded).

**When to pick this.** After C works; this is the harness-specific payoff.

### E — Model-visible SQL world (guarded)

**What.** A `store_query` (read-only) tool over **views**, not raw tables.
Allowlist: `v_messages`, `v_turns`, `v_tool_calls`, `v_files`,
`v_workflow_artifacts`. Limits, timeouts, no `COPY`, no `INSTALL`, no
`httpfs`. Results are clamped JSON/EDN for the model and a Portal table
for the human.

**Fits.** “The agent can ask its own memory questions” without inventing
twenty retrieval tools. Complements `runtime_describe` (which is a redacted
ctx dump, not a query language).

**Must not do.** Arbitrary SQL against the live connection. No secret
views. No write tools until we have a migration/version story. SCI
runtime-authored tools must not receive the JDBC connection — only
`lateralus.runtime/call-tool` to the allowlisted `store_query`.

**When to pick this.** After C (and probably D) have a stable schema.
This is a product feature, not a storage spike.

### Explicit non-options

| Idea | Why not |
|------|---------|
| Replace the interceptor chain / state atom with SQL transactions | Violates the locked engine. DuckDB cannot be the ReAct loop. |
| Put secrets in DuckDB | Breaks use-without-seeing; sealed store stays separate. |
| Default native-image to DuckDB | Native lib + extension load is unproven in *this* repo. Keep `:kg-bm25`. |
| MotherDuck / remote DuckDB | New network boundary, account, and egress. Out of scope. |
| Auto-`INSTALL` extensions on first run | Violates air-gapped default. Vendor or disable. |

## Comparison to the other SQL/graph candidates

| | DuckDB | SQLite + sqlite-vec | Datalevin | Proximum + files (today) |
|--|--------|---------------------|-----------|---------------------------|
| Role | Embedded OLAP + FTS + optional HNSW | Embedded OLTP + vec extension | Datalog + LMDB + usearch | HNSW (JVM) + EDN/Konserve |
| Clojure feel | JDBC maps | JDBC maps | EDN-native Datalog | EDN / Konserve |
| BM25 | `fts` extension | FTS5 | Built-in FTS | Hand-rolled in `:kg-bm25` |
| Vectors | `vss` experimental, persist flag | `sqlite-vec` C ext | usearch | Proximum (solid on JVM) |
| Analytics / traces | Excellent | Adequate | Awkward | None |
| Native-image | Documented JDBC path; still a `.so` | Possible; packaging pain | JNI/LMDB — already rejected for MVP | Proximum **excluded**; KG-BM25 yes |
| Air-gap | Must vendor extensions | Single amalgamation | Native lib in JAR | Pure Clojure KG-BM25 |
| Multi-process write | No | Yes (with care) | Yes | File locks / Konserve |
| Already in-tree | No | No | Explicitly no | Yes |

**SQLite** is the better “boring row store” if the only goal is ACID
catalog + messages and we do not care about analytical SQL or first-class
BM25. **DuckDB** wins if the point of a core store is *querying the
harness* (traces, edits, recall, Portal tables) from one file.
**Datalevin** remains the most complete Clojure-native graph+vector option
and remains the wrong default (JNI, larger classpath, already out of MVP).

A reasonable long-term shape is **DuckDB for rows + FTS + traces**,
**Proximum or DuckDB-VSS for dense vectors on JVM**, **KG-BM25 for
native-image**. Those are backends, not competing engines.

## How this maps onto existing protocols

No new model-facing capability should talk JDBC. The leaf looks like this:

```
interceptors / tools
        │
        ▼
MemoryBackend / SessionStore / StreamBus / WorkflowEngine / (FileIndex)
        │
        ▼
StoreEngine          ← new, local I/O, Malli on execute/query
        │
        ▼
duckdb_jdbc  |  in-memory fake for unit tests
```

`StoreEngine` is **not** a network boundary. It still gets:

1. A protocol.
2. Closed Malli schemas for query/execute input and row output.
3. `m/=>` + `malli.instrument` on the JDBC leaf and constructor.
4. Tests that inject a fake engine (no native lib in the default suite
   unless we accept it the way we already accept Proximum on `:test`).

Integrant: `:lateralus/store` with `ig/assert-key`, `ig/init-key` (open
file or `:memory:`), `ig/halt-key!` (close). JVM configs opt in; 
`resources/lateralus/native.edn` stays on KG-BM25 until a native spike
passes.

## Suggested schema (Option C, first cut)

Enough to be useful; not a migration framework.

```sql
CREATE TABLE sessions (
  id VARCHAR PRIMARY KEY,
  title VARCHAR,
  created_at BIGINT,
  updated_at BIGINT,
  preview VARCHAR,
  agent_state JSON,          -- export-state snapshot
  current BOOLEAN
);

CREATE TABLE messages (
  session_id VARCHAR,
  msg_id VARCHAR,
  role VARCHAR,
  content VARCHAR,
  ts BIGINT,
  embedding FLOAT[],         -- nullable; JVM + embedder only
  PRIMARY KEY (session_id, msg_id)
);

CREATE TABLE turns (
  id VARCHAR PRIMARY KEY,
  session_id VARCHAR,
  status VARCHAR,
  opened_at BIGINT,
  closed_at BIGINT,
  user_text VARCHAR,
  text VARCHAR,
  thinking VARCHAR,
  model VARCHAR,
  usage JSON
);

CREATE TABLE events (
  turn_id VARCHAR,
  seq INTEGER,
  type VARCHAR,
  payload JSON,
  PRIMARY KEY (turn_id, seq)
);
```

FTS index on `messages(content)` when the vendored `fts` extension is
present; otherwise last-N + `ILIKE` / Clojure BM25 fallback. KG tables can
wait until we port `:kg-bm25` entity extraction onto SQL, or we keep the
Clojure graph in-process and only persist messages here.

## Phased path (if we proceed)

Do not implement E in the first card. Each phase is a protocol façade plus
tests; the chain does not change.

1. **Spike (this is the next card, if approved).** Add `org.duckdb/duckdb_jdbc`
   behind a `:dev` or optional alias. Open an in-memory DB from Clojure,
   `CREATE TABLE`, round-trip a message map, `ig/halt-key!` closes. Measure
   JAR weight and whether `:test` JVM flags need anything new (they should
   not — no Vector API). Document extension load *without* network
   (`LOAD` from a path, or skip).
2. **Option A.** `:impl :duckdb` `MemoryBackend` + file path store. Parity
   tests against the KG-BM25 / Proximum contract (`-store-message`,
   `-recall-hybrid`, `-close`). Keep Proximum as JVM default until recall
   quality is measured.
3. **Option C without files (shipped, opt-in).** Point `SessionStore` and
   historic `StreamBus` at the same `:lateralus/store`. File `catalog.edn`
   remains the default; `:lateralus/session-store {:store …}` and
   `:lateralus/stream-bus {:impl :store :store …}` select the façades.
   Live SSE stays capped in RAM; closed turns are checkpointed.
4. **Option D.** File index + edit log hooked from existing mutation
   tools (they already have witnesses). `file_search` FTS path is opt-in.
5. **Option E.** Read-only `store_query` + Portal viewer. Air-gapped views
   only.
6. **Native-image (optional, last).** Follow DuckDB’s JDK 22 native-image
   docs; vendor `libduckdb_java` + `fts` or keep native on KG-BM25 forever.
   Either outcome is acceptable.

## Open questions

These are product/architecture choices, not research gaps:

1. **Is “center” persistence or control flow?** This note assumes
   persistence. If the intent was “SQL *is* the agent loop,” that is a
   different project and conflicts with the locked interceptor engine.
2. **One `.duckdb` per user vs per session?** One file per workbench
   (sessions as rows) matches the catalog. Per-session files match
   KG-BM25’s directory layout and make delete trivial. Recommendation:
   one file per config `:path`, sessions as rows.
3. **Do we still want Proximum?** Yes, until DuckDB VSS persistence is
   boring. DuckDB does not have to win vectors to win traces + FTS +
   catalog.
4. **Default JVM config?** Switching `resources/lateralus/config.edn` off
   Proximum is a user-visible recall change. First ship should be an
   opt-in profile (`resources/lateralus/demo-duckdb.edn`), not a default
   flip.
5. **Accept `duckdb_jdbc` on the default test classpath?** Proximum is
   already there. Adding another native extract (DuckDB’s `.so`) slows
   CI and complicates the fast suite. Prefer an alias or a pure fake
   `StoreEngine` for unit tests; one `^:duckdb` integration ns.

## Suggested next step

Do **not** start a schema migration or swap the default memory backend.
If this direction is accepted, the next unit of work is the **JDBC spike +
`StoreEngine` protocol** (phase 1): no tools, no recall quality claims, no
native-image, halt/close covered, air-gap extension policy written down.

Until then, KG-BM25 and Proximum remain the supported backends; SQLite +
sqlite-vec stays the documented alternative if we decide we want OLTP more
than analytics.

## Code / doc links

- Locked engine: [`docs/architecture.md`](./architecture.md)
- Memory protocol and backends: [`docs/memory-v2.md`](./memory-v2.md)
- Prior SQL/graph decision log: [`docs/memory-backend-research.md`](./memory-backend-research.md)
- Embedding-free recall: [`docs/memory-embedding-free-alternatives.md`](./memory-embedding-free-alternatives.md)
- Network / protocol rule: [`docs/network-boundaries.md`](./network-boundaries.md)
- File harness contract: [`goals/interceptor-runtime-file-harness/goal.md`](../goals/interceptor-runtime-file-harness/goal.md)
- `MemoryBackend`: `src/kschltz/agent/memory/protocol.clj`
- `SessionStore`: `src/kschltz/agent/session/protocol.clj`
- `StreamBus`: `src/kschltz/agent/stream/protocol.clj`
- `WorkflowEngine`: `src/kschltz/agent/tools/workflow/protocol.clj`
- `SecretStore` (must stay separate): `src/kschltz/agent/secrets.clj`
