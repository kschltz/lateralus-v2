# Step 6 Memory Backend — Research Notes

This document surveys lightweight embedded options for a real
`MemoryBackend` implementation in lateralus-v2. The MVP ships a
noop backend; a real backend is a follow-up. For embedding-free
alternatives, see [`memory-embedding-free-alternatives.md`](./memory-embedding-free-alternatives.md).

The criteria are:

1. **Speed** — low-latency read/write for session-sized data.
2. **Lightweight embedded setup** — single-file or small-directory,
   no external server process.
3. **Hybrid recall** — the protocol needs `top-Y semantic + last-N
   recent`, deduped and sorted. So the store needs both structured
   message storage and vector similarity search.
4. **Clojure ergonomics** — existing Clojure bindings, EDN-friendly
   data, minimal ceremony.
5. **GraalVM / native-image friendliness** — ideally pure JVM or
   well-supported JNI, not a hard blocker for the Step 9 stretch.

## Option matrix

| Option | Storage | Vector search | Pros | Cons | Verdict |
|--------|---------|---------------|------|------|---------|
| **Datalevin** | LMDB + Datalog | Built-in (usearch SIMD HNSW) | Fast, EDN-native, Datalog queries, vector + full-text in one, has `datalevin-embedded` trim | JNI/LMDB native lib, larger dep, historically removed from MVP for complexity | Powerful but heavy; best if we want one DB for everything |
| **SQLite + sqlite-vec** | Single SQLite file | `sqlite-vec` C extension (KNN virtual table) | Ubiquitous, single file, excellent tooling, SQL/Datalog optional | sqlite-vec is a native C extension; distribution story on JVM is packaging the `.so`/`.dylib`/`.dll`; no pure-JVM | Strongest "boring" choice if we accept bundling a native extension |
| **Proximum** | Pluggable (memory/file) via Konserve | Pure-JVM HNSW | Pure JVM (Panama Vector API + MemorySegment), no native deps, immutable/git-like, Clojure-native API, competitive benchmarks | Java 22+ required, `--add-modules=jdk.incubator.vector` + `--enable-native-access=ALL-UNNAMED`, very new (beta), pulls Konserve/hasch/malli, only a vector index | Best pure-JVM vector index; pair with SQLite/Datalevin for structured data |
| **clj-rocksdb** | RocksDB directory | None (use a separate vector index) | Mature, fast, simple KV API, EDN via nippy | No vector search; needs a second component for semantic recall | Good if we build/borrow a small vector index |
| **Asami** | In-memory or local graph | None | Datomic-like API, durable local storage, pure Clojure | No vector search; durable storage less mature | Graph model is nice but we'd still need vectors |
| **Flat files (EDN/JSON lines)** | One file per session | In-memory brute force or separate index | Zero deps, trivial to inspect/debug | Doesn't scale, no ACID, reinventing a DB | Good for tests/prototyping only |
| **LMDB raw (`lmdbjava`/`cinq`)** | LMDB directory | None | Extremely fast reads, single-file-ish, cinq gives Clojure queries | JNI, no vectors, low-level | Building block; not a full solution |

## Detailed notes

### 1. Datalevin

The v1 lateralus plan originally considered Datalevin. It was dropped
from v2 MVP because it brings a JNI dependency and complicates
GraalVM. But for a real backend it is still the most *complete*
Clojure-native option:

- Datalog query language matches the project's immutable-data ethos.
- Built-in vector search (usearch) and full-text search.
- `org.datalevin/datalevin-embedded` strips server/HA/pod pieces,
   leaving just the embedded library.
- EDN data storage is first-class.

Trade-off: it's the heaviest dependency and has native code. If the
project later wants "one embedded DB that does messages + vectors +
full-text", Datalevin is the answer.

### 2. SQLite + sqlite-vec

This is the "boring" RAG stack that has become popular in 2024-2025.
It scores highest on ecosystem maturity:

- SQLite is everywhere; single `.sqlite3` file per session or one
  global file.
- `sqlite-vec` adds a `vec0` virtual table for float/int8/bit vector
  KNN search. It is written in pure C with no dependencies and runs
  anywhere SQLite does (including WASM).
- SQL schema is simple: a `messages` table + a `vec0` table keyed by
  `rowid`.
- Hybrid recall becomes one query:

  ```sql
  SELECT m.*, v.distance
  FROM messages m
  JOIN vec_scores v ON v.rowid = m.id
  WHERE v.query_embedding MATCH ? AND v.k = ?
  UNION
  SELECT * FROM (
    SELECT * FROM messages
    WHERE session_id = ?
    ORDER BY timestamp DESC LIMIT ?
  )
  ORDER BY timestamp;
  ```

The main friction is that `sqlite-vec` is a C extension. On the JVM you
need to either:
- ship a precompiled `vec0.dylib`/`vec0.so`/`vec0.dll` alongside the
  application, or
- use a SQLite distribution that already bundles it (e.g. some custom
  builds), or
- load the extension from the classpath (possible with `Xerial SQLite
  JDBC` + `SELECT load_extension(...)` if the binary is on disk).

For a JVM project this is doable but adds native artifact packaging.
It is *not* a pure-JVM solution.

### 3. Proximum

`org.replikativ/proximum` is a new (2026) pure-JVM vector database
from the Replikativ team. Latest release on Clojars is **0.1.25**
(May 2026); GitHub latest is 0.1.24 (Mar 2026). It is explicitly
marked **early beta**.

What it is:
- HNSW index written in Java using the **Panama Vector API** and
  **Foreign Function & Memory API** (`MemorySegment`, mmap).
- Immutable/persistent data-structure semantics: every `assoc`
  returns a new index version; old versions remain queryable.
- Git-like versioning: `sync!` creates commits, `branch!` forks,
  `load-commit` time-travels.
- No native dependencies (pure JVM) — the strongest point for
  GraalVM native-image, especially now that FFM API is supported in
  Native Image by default starting with GraalVM 25.
- Clojure-native API: implements `IPersistentMap`, `ILookup`,
  `Seqable`, `ITransientMap`. Insert with `assoc`, search with
  `prox/search`, get by ID with `get`.
- Pluggable storage via **Konserve** (`:memory`, `:file`, S3, etc.).
  Persistence uses chunked vectors + PersistentEdgeIndex + two
  PersistentSortedSets for metadata and external-id → node-id.
- Performance (published SIFT-1M): ~13.4k inserts/s, ~3.8k search
  QPS, 98.6% recall@10 — competitive with jvector and datalevin/usearch.

Caveats:
- Requires **Java 22+**. Running needs two JVM flags:
  ```bash
  --add-modules=jdk.incubator.vector
  --enable-native-access=ALL-UNNAMED
  ```
- Very new; API may change before 1.0.
- Not a one-stop shop: it is *only* a vector index. Structured message
  data, recent-N queries, and metadata filtering need a companion store
  (SQLite, Datalevin, or even flat EDN).
- Dependency footprint is non-trivial: pulls `org.replikativ/konserve`,
  `hasch`, `malli`, `persistent-sorted-set`, `yggdrasil`, `core.cache`,
  `taoensso/trove`, `slf4j-api`.
- Native-image support for FFM API is promising but still requires
  reachability metadata and `--enable-native-access` flags. It is less
  battle-tested than Datalevin's well-known JNI/LMDB path.

Recommended pairings:
- **Proximum + SQLite** — pure-JVM vectors + a battle-tested relational
  store in a single SQLite file. Best for GraalVM and minimal native code.
- **Proximum + Datalevin** — both are Clojure-native and share the
  Replikativ ecosystem, but this doubles the dependency weight.

### 4. clj-rocksdb

`io.replikativ/clj-rocksdb` is a thin Clojure wrapper around RocksDB:

- Mature, fast LSM-tree embedded KV store.
- Nippy encoding gives transparent EDN serialization.
- No vector search; you would store embeddings as values and do
  brute-force or maintain a separate HNSW index.

Best used as a building block, not a complete solution. The "separate
vector index" problem is exactly what Proximum/sqlite-vec/Datalevin
solve.

### 5. Asami

Asami is a schemaless graph database with a Datomic-like API, durable
local storage, and pure Clojure. It is pleasant for graph-shaped data
but has no vector index. Like RocksDB, it would need a companion
vector library.

### 6. Flat files (EDN/JSON lines)

The absolute minimum. A session is a directory or file of messages;
recall is brute-force in memory. This is fine for prototyping and
very small sessions, but it does not satisfy "speed" or "embedded
DB" once sessions grow. Useful for a `:file` backend used only in
tests or demos.

## Recommendations

### For a quick, lightweight real backend

**SQLite + sqlite-vec** is the strongest candidate. It gives:
- single-file storage,
- ACID transactions,
- SQL/Datalog queries,
- built-in vector search,
- trivial backup (copy the file).

The native extension packaging is the only real downside. For a JVM
project that already accepts native libs via hato/JNA/etc., this is
manageable.

### For a pure-JVM, native-image-friendly backend

**Proximum + SQLite** is the best combination:
- SQLite handles structured message metadata and recent-N recall.
- Proximum handles the HNSW semantic index.
- Both can be embedded; Proximum has no native code; SQLite's native
  image story is well understood (GraalVM has SQLite reflection
  configs in the ecosystem).

### If we want exactly one dependency

**Datalevin** (`datalevin-embedded`) does messages + vectors +
full-text in a single library. It is the most Clojure-native. The
cost is the LMDB native dependency and a larger classpath.

### If we want the absolute minimum change to the MVP

Keep the noop backend and add a **file-based "recent only" backend**
that stores all messages per session in a single EDN file and recalls
the whole session (no semantic ranking, just last-N). This is not a
real "memory backend" in the RAG sense, but it satisfies
conversation-history across exchanges with zero new dependencies.

## Open questions before committing

1. Do we need semantic recall in this follow-up, or is recent-N +
   maybe full-session recall enough? If semantic recall is required,
   we need vectors; if not, SQLite/EDN-only is simpler.
2. What's the embedding story? The `Embedder` protocol exists but
   only has a noop impl. Any real backend needs an HTTP or ONNX
   embedder first.
3. Native-image target? If yes, avoid Datalevin and prefer
   Proximum/SQLite.
4. Single global DB vs. one DB per session? A single SQLite file is
   simpler; one directory per session matches the v2 schema sketch.

## Suggested next step

Spike a **SQLite + sqlite-vec** prototype behind a new
`:lateralus/memory-backend {:impl :sqlite-vec :path "..."}` Integrant
config. It directly exercises the `MemoryBackend` protocol and the
`:enrich`/`:persist` plugin slots. If the native-extension packaging
proves too hard in one session, evaluate **Proximum + SQLite** for a
pure-JVM/GraalVM-friendly path, or **Datalevin** for a single-dependency
Clojure-native stack.
