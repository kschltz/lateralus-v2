# Memory Backend Research — Decision Log

**Status: decision log — superseded in part by implemented backends.**

Three `MemoryBackend` implementations now exist in the codebase:

- **`noop`** — test default; no persistence, recalls `[]`.
- **`proximum`** — JVM runtime default in `resources/lateralus/config.edn`. Pure-JVM HNSW vector index + message metadata. Requires Java 22+ and incubator JVM flags; not compatible with GraalVM native-image.
- **`kg-bm25`** — native-image default in `resources/lateralus/native.edn`. Embedding-free, pure-Clojure BM25 + small knowledge-graph backend. No native dependencies.

This document records the original research and trade-offs. For the implemented backend design, see [`docs/memory-v2.md`](./memory-v2.md) and [`docs/memory-embedding-free-alternatives.md`](./memory-embedding-free-alternatives.md).

## Option matrix

| Option | Storage | Vector search | Embeddings | Native-image | Pros | Cons | Verdict |
|--------|---------|---------------|------------|--------------|------|------|---------|
| **noop** | none | none | none | yes | Zero deps, instant tests | No real recall | Test default only |
| **kg-bm25** | In-memory or file-backed EDN | BM25 keyword + small entity graph | none | yes | Pure Clojure, no native deps, no JVM flags | Keyword-only recall; no dense vectors | Current native-image default |
| **Proximum** | Pluggable (memory/file) via Konserve | Pure-JVM HNSW | dense (LangChain4j ONNX) | no | Pure JVM (Panama Vector API), immutable/git-like | Java 22+, incubator flags, beta, pulls heavy deps | Current JVM default |
| **Datalevin** | LMDB + Datalog | Built-in usearch HNSW | dense (HTTP/ONNX) | no | Fast, EDN-native, Datalog, vector + full-text | JNI/LMDB native lib, larger dep | Powerful but heavy; future option |
| **SQLite + sqlite-vec** | Single SQLite file | `sqlite-vec` C extension | dense | unclear | Ubiquitous, single file, SQL | Native C extension packaging on JVM | Future option if native extension packaging is acceptable |
| **clj-rocksdb** | RocksDB directory | None | none | no | Mature, fast KV, EDN via nippy | No vector search; needs second component | Building block only |
| **Asami** | In-memory or local graph | None | none | yes | Datomic-like API, pure Clojure | No vector search; durable storage less mature | Graph model only |
| **Flat files** | One file per session | In-memory brute force | none | yes | Zero deps, trivial to inspect | Doesn't scale, no ACID | Tests/prototyping only |
| **LMDB raw** | LMDB directory | None | none | no | Extremely fast reads | JNI, low-level, no vectors | Building block only |

## Notes on implemented backends

### noop

`kschltz.agent.memory.noop-backend` satisfies `MemoryBackend` with no-ops. It is the default in `system/default-config` so the fast unit test suite stays isolated from disk and network.

### KG + BM25

`kschltz.agent.memory.kg-bm25-backend` is the native-image default. It is embedding-free and therefore safe for GraalVM, where the LangChain4j ONNX embedder and Proximum's Panama-based HNSW index are unavailable.

- Storage: per-session `messages.edn` + `index.edn` under the configured `:store` path.
- Recall: recent-N + RRF fusion of BM25(query-text) and knowledge-graph entity overlap.
- Config: see `resources/lateralus/native.edn` and [`docs/memory-v2.md`](./memory-v2.md).

### Proximum

`kschltz.agent.memory.proximum-backend` is the JVM runtime default, paired with the LangChain4j in-process ONNX embedder.

- Requires Java 22+ and `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED`.
- Not available in the native-image build; its source file is excluded from the filtered native classpath.

## Notes on future backends

### Datalevin

The v1 plan originally considered Datalevin. It was dropped from v2 MVP because it brings a JNI dependency and complicates GraalVM. For a future backend it is still the most complete Clojure-native option: Datalog queries, built-in usearch vector search, and full-text search, with `org.datalevin/datalevin-embedded` trimming server/HA pieces. The cost is the LMDB native dependency and larger classpath.

### SQLite + sqlite-vec

The "boring" RAG stack. SQLite gives single-file ACID structured storage; `sqlite-vec` adds a `vec0` virtual table for KNN search. The friction is packaging the C extension for JVM distribution.

### clj-rocksdb / Asami / LMDB raw

These are building blocks without vector search. They would need a companion vector index (Proximum, sqlite-vec, or a brute-force in-memory index) to implement semantic recall.

## Resolved and unresolved questions

**Resolved:**

1. Semantic recall for native-image — solved by `:kg-bm25` using BM25 + knowledge graph instead of dense vectors.
2. Embedder story — the `Embedder` protocol has `:noop`, `:http`, and `:langchain4j` implementations. Native-image uses `:noop` or `:http`.
3. Native-image target — yes; achieved by excluding Proximum/LangChain4j sources and defaulting to `:kg-bm25` + `:noop`.
4. Single global DB vs. one store per session — KG-BM25 uses one directory per session; Proximum uses a single Konserve store with session-id metadata.

**Future backends:**

1. Do we need a dense-vector backend in native-image? If yes, add an HTTP embedder to the native config (already supported via `kschltz.agent.memory.http-embedding`).
2. Do we want a single-dependency Clojure-native backend for JVM? Datalevin remains the candidate.
3. Do we want a SQL-backed backend? SQLite + sqlite-vec is the candidate if native-extension packaging is acceptable.

## Suggested next step

Stabilize the existing `:kg-bm25` backend rather than starting a new SQLite spike. The active kanban card **"Refactor KG-BM25 backend into focused namespaces"** is the right next unit of work. See `src/kschltz/agent/memory/kg_bm25_backend.clj` and [`docs/memory-v2.md`](./memory-v2.md) for current behavior.

## Code links

- Protocol: `src/kschltz/agent/memory/protocol.clj`
- Noop backend: `src/kschltz/agent/memory/noop_backend.clj`
- Proximum backend: `src/kschltz/agent/memory/proximum_backend.clj`
- KG-BM25 backend: `src/kschltz/agent/memory/kg_bm25_backend.clj`
- HTTP embedder: `src/kschltz/agent/memory/http_embedding.clj`
- LangChain4j embedder: `src/kschltz/agent/memory/langchain4j_embedding.clj`
- JVM runtime config: `resources/lateralus/config.edn`
- Native-image runtime config: `resources/lateralus/native.edn`
