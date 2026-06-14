# Embedding-Free Agentic Memory Alternatives for `lateralus-v2`

This document catalogs **concrete, open-source agentic memory systems that do not rely on vector embeddings** and maps them to the existing `MemoryBackend` protocol. It is a companion to [`memory-backend-research.md`](./memory-backend-research.md), which focuses on vector/embedding-based stores.

## Why this matters for lateralus-v2

The current runtime default uses **Proximum + LangChain4j ONNX embeddings** for session memory. That stack is powerful but carries:

- a Java 22+ requirement and incubator-vector JVM flags,
- an in-process ONNX model (problematic for GraalVM native-image),
- a hard dependency on floating-point embeddings.

For deployments that need a smaller native-image footprint, simpler dependencies, or symbolic/interpretable recall, we want backends that can satisfy the same `MemoryBackend` protocol without ever producing an embedding vector.

## Mapping `-recall-hybrid` to a non-embedding world

The protocol requires:

```clojure
(-recall-hybrid [backend session-id {:keys [top-y last-n query-text query-embedding]}])
```

It must return `top-Y relevant` + `last-N recent` messages, deduped by `:msg-id` and sorted by `:timestamp`.

In an embedding-free backend we **replace the semantic-Y channel** with a symbolic relevance channel:

| Semantic equivalent | How it works |
|---------------------|--------------|
| Sparse text retrieval | BM25 / TF-IDF / keyword overlap over message `:content` |
| Keyword/entity match | Extract entities from `query-text`, match against stored tags/entities |
| Knowledge-graph traversal | Walk a session KG from query entities to related messages |
| Graph PageRank | Personalized PageRank over entities/claims to surface relevant passages |
| LLM reranking | Generate a candidate set with any method above, then ask the LLM to pick the best `top-y` |
| Episodic summary matching | Match against pre-written summaries, failures, or skill descriptions |

The `last-N` channel is unchanged: filter by `:session-id`, sort by `:timestamp`, take the tail.

Because the protocol explicitly allows backends to ignore `:query-embedding`, an embedding-free backend can operate entirely from `:query-text`.

---

## 1. Knowledge graph + sparse retrieval

These systems build a symbolic graph (entities, relations, claims, evidence) and retrieve through graph traversal or sparse text ranking.

### AriGraph

- **Repository**: `AIRI-Institute/AriGraph`
- **Paper**: *AriGraph: Learning Knowledge Graph World Models from Text* — IJCAI 2025, arXiv:2407.04363
- **License**: MIT
- **Mechanism**: Maintains an **active incremental knowledge graph** as an LLM agent explores a text world. Nodes are objects/locations/facts; edges are relations. It also keeps an **episodic memory** of observed events and uses the graph to plan and answer questions.
- **Strengths**: Purely symbolic world model; interpretable; combines episodic and semantic memory in one structure.
- **Limitations**: Reference implementation is Python/PyTorch/TextWorld-centric; built for embodied/text-world agents rather than chat sessions.
- **Mapping to `MemoryBackend`**:
  - `-store-message` → add the message as an episodic node and, optionally, extract entities/relations into the session KG.
  - `-recall-hybrid` → use query keywords/entities to seed a graph walk, return messages attached to visited nodes, then merge with last-N.
- **Clojure/native fit**: Pattern only. The graph can be implemented with Asami, Datalevin, or a custom in-memory map index. No native code required.

### HippoRAG

- **Repository**: `OSU-NLP-Group/HippoRAG`
- **Paper**: *HippoRAG: Neurobiologically Inspired Long-Term Memory for Large Language Models* — NeurIPS 2024, arXiv:2405.14831
- **License**: MIT
- **Mechanism**: Uses an LLM to extract entities and relations, links them to an external KG (e.g., UMLS/WordNet), then runs **Personalized PageRank** over the combined graph to retrieve relevant passages. HippoRAG 2 adds continual learning and better long-horizon accumulation.
- **Strengths**: Retrieval is a graph algorithm, not a vector similarity; strong for long-document/long-horizon tasks; explicitly designed as an embedding-free alternative to dense retrieval.
- **Limitations**: Reference code is Python and depends on NLP/KG tools (spaCy, Stanford CoreNLP, etc.); external KG is domain-specific.
- **Mapping to `MemoryBackend`**:
  - `-store-message` → LLM-extract `(entity, relation, entity)` triples and store them with a pointer back to the source message.
  - `-recall-hybrid` → run PageRank from query entities, collect the highest-ranked messages, merge with last-N.
- **Clojure/native fit**: Implement the pattern with a small in-memory or SQLite-backed directed graph and a PageRank iteration. No native dependencies. A session-sized graph is fast enough to re-rank on every exchange.

### `agent-knowledge` / `compiled-memory`

- **Repository**: `yucx-go/agent-knowledge`
- **Package**: `compiled-memory` on PyPI
- **Tagline**: *Long-term memory without vector embeddings*
- **License**: MIT
- **Mechanism**: Builds a **claim/evidence knowledge graph**, indexes evidence with **BM25**, and uses **Reciprocal Rank Fusion (RRF)** to combine KG traversal and sparse text scores. Includes provenance and an MCP server.
- **Strengths**: Explicitly designed around the same constraint we have; combines KG structure with battle-tested sparse IR; provenance per claim.
- **Limitations**: Python implementation; BM25/RRF are easy to port, but the claim-extraction logic is LLM-driven.
- **Mapping to `MemoryBackend`**:
  - `-store-message` → store raw message; optionally extract claims and link them to evidence (the message).
  - `-recall-hybrid` → run BM25 over message text and RRF with any KG-derived scores; return top-y messages.
- **Clojure/native fit**: Excellent. SQLite + a Clojure inverted index + RRF is a small, pure-JVM backend. This is the closest architectural blueprint to a native-image-friendly `lateralus-v2` memory backend.

### LangChain `ConversationKGMemory`

- **Reference**: `langchain.memory.ConversationKGMemory`
- **License**: MIT (LangChain)
- **Mechanism**: Extracts entities and knowledge triples from the conversation and stores them in a NetworkX graph. Recall returns related facts and recent messages.
- **Strengths**: Classic, well-documented pattern; no embeddings; easy to reason about.
- **Limitations**: Python-only; entity extraction quality depends on the LLM; graph is local and ephemeral unless persisted.
- **Mapping to `MemoryBackend`**: Same as AriGraph/HippoRAG but smaller scale — a session KG plus last-N.
- **Clojure/native fit**: Pattern only. A few helper functions + an atom/Asami/Datalevin graph + a timestamp index are enough to reproduce it.

---

## 2. Sparse text / keyword indexes

These systems keep memory as text buffers and retrieve through lexical or rule-based ranking.

### AutoGen `ListMemory` / text memory

- **Reference**: `autogen_core.memory` (AutoGen v0.4)
- **License**: MIT
- **Mechanism**: Provides a `Memory` protocol with pluggable stores. The simplest implementations are **list/text memory**: scan all stored entries and return those matching a query string (substring or regex), then take the most recent.
- **Strengths**: Zero embedding cost; trivial to implement; works anywhere.
- **Limitations**: No semantic generalization beyond the chosen matching rule; scales linearly.
- **Mapping to `MemoryBackend`**: Store messages in a list/file; `-recall-hybrid` returns top-y substring/keyword matches + last-n.
- **Clojure/native fit**: Perfect. Pure Clojure, no dependencies, native-image-safe.

### BM25 + inverted index

- **Reference**: Classic IR building block; usable via Apache Lucene, `whoosh` (Python), or a small Clojure implementation.
- **Mechanism**: Tokenize messages, build an inverted index of term → messages, score candidates with BM25 against `query-text`.
- **Strengths**: Fast, interpretable, no vectors, scales to tens of thousands of messages.
- **Limitations**: Lexical only; synonyms and paraphrases need extra handling (query expansion, LLM reranking).
- **Mapping to `MemoryBackend`**: The top-y channel is the highest BM25-scored messages for `query-text`.
- **Clojure/native fit**: Very good. Apache Lucene is pure Java and has existing GraalVM reachability metadata. A custom Clojure inverted index is even smaller.

---

## 3. Episodic / reflective / procedural memory

These systems are less about drop-in storage and more about **what** the agent remembers (failures, skills, plans).

### Reflexion

- **Reference**: *Reflexion: Self-Reflective Agents* — arXiv:2303.11366
- **License**: Various implementations (e.g., `noahshinn024/reflexion`, MIT)
- **Mechanism**: The agent writes a **reflective episodic memory** (short verbal lessons) after failures. Future episodes are conditioned on the relevant reflection.
- **Strengths**: No embeddings; improves long-horizon task success; interpretable.
- **Limitations**: Needs a meta-eval loop to know *when* to reflect; not a general chat-history backend.
- **Mapping to `MemoryBackend`**: Reflections can be stored as messages with a `:kind :reflection` tag. `-recall-hybrid` matches reflections by task keyword, then merges with last-N chat messages.
- **Clojure/native fit**: Pattern only. Adds no dependencies beyond existing LLM calls.

### Voyager skill library

- **Reference**: *Voyager: An Open-Ended Embodied Agent with Large Language Models* — arXiv:2305.16291
- **License**: Various community implementations
- **Mechanism**: Procedural memory as a library of **executable skill programs**. Skills are retrieved by description and by the situation in which they apply.
- **Strengths**: Turns memory into reusable, verifiable code; no embeddings required for matching if skill descriptions are indexed lexically.
- **Limitations**: Designed for embodied/tool-use agents; skills are programs, not chat messages.
- **Mapping to `MemoryBackend`**: Not a direct backend replacement. Better implemented as a separate **skill-library plugin** that the agent can `search-skills` and `add-skill`.
- **Clojure/native fit**: Excellent if skills are Clojure functions stored as EDN/code files.

---

## 4. Hierarchical / context-paging memory

These systems change **how** context is assembled, not just how storage is searched.

### MemGPT / Letta

- **Repository**: `deductive-ai/MemGPT` (MemGPT) → `letta-ai/letta` (Letta)
- **License**: Apache 2.0
- **Mechanism**: Treats the LLM context window like an OS memory hierarchy: a small **main context**, a larger **archival memory**, and a **recall queue**. The model itself edits memory pages and decides what to page in/out.
- **Strengths**: Gives the LLM explicit control over what stays in context; naturally handles very long sessions.
- **Limitations**: Letta is a Python server/service and its archival store often uses vector search; the architecture is a **context manager**, not a narrow storage backend.
- **Mapping to `MemoryBackend`**: Not a drop-in `-recall-hybrid` backend swap. It would require a new `:context-manager` component that exposes `retrieve`, `edit`, and `evict` operations to the agent loop.
- **Clojure/native fit**: Adopt the pattern, not the dependency. A Clojure agent could implement a two-tier store (recent-N in-context + archival SQLite/EDN) and expose memory tools.

---

## Comparison matrix

| System | Category | Embedding-free? | Drop-in `MemoryBackend`? | Main Clojure building blocks | Native-image risk |
|--------|----------|-----------------|--------------------------|-------------------------------|-------------------|
| **AriGraph** | KG world model | Yes | Pattern only | Asami / custom graph | Low |
| **HippoRAG** | KG + PageRank | Yes | Pattern only | Custom graph + PPR | Low |
| **`compiled-memory`** | KG + BM25 + RRF | Yes | **Strong blueprint** | SQLite + inverted index + RRF | Low |
| **LangChain KG Memory** | Session KG | Yes | Pattern only | Asami / custom graph | Low |
| **AutoGen text memory** | Text list | Yes | Yes | Atom / vector / EDN file | None |
| **BM25 inverted index** | Sparse IR | Yes | Yes | Apache Lucene or custom index | Low |
| **Reflexion** | Reflective episodic | Yes | Pattern only | Tagged messages + keyword match | None |
| **Voyager skills** | Procedural memory | Yes | No (separate plugin) | EDN/code library | None |
| **MemGPT / Letta** | Hierarchical paging | Partial / pattern | No (needs context manager) | Two-tier store + memory tools | High if importing Letta |

---

## Recommendations

### Immediate: implement a `:bm25-text` `MemoryBackend`

The lowest-risk embedding-free backend is a **sparse-text backend** that stores messages in SQLite (or an EDN/JSONL file per session) and runs BM25/keyword ranking for the top-y channel.

- Satisfies the existing protocol with zero consumer changes.
- No JVM flag or Java-version requirement.
- Pure Clojure (custom inverted index) or pure Java (Apache Lucene).
- Native-image-friendly, especially if SQLite is avoided in favor of plain files.

Suggested default design:

```clojure
{:lateralus/memory-backend
 {:impl :bm25-text
  :store {:backend :file :path "sessions/bm25"}
  :top-y 5 :last-n 10}}
```

`-store-message` appends to the session file/index. `-recall-hybrid` runs BM25 over `query-text`, takes top-y, merges with last-n by timestamp, dedupes by `:msg-id`.

### Medium-term: implement a `:kg-bm25` backend

Add a small **session knowledge graph** on top of the text store:

1. On store, optionally extract `(entity, relation, entity)` triples (via LLM or rules).
2. Index messages in BM25; link triples to messages.
3. On recall, seed graph traversal from query entities and RRF the graph scores with BM25 scores.

This captures the essence of `compiled-memory`, HippoRAG, and AriGraph without pulling in Python. It is still pure JVM and fits the protocol.

### Orthogonal: reflective & procedural memory as plugins

- **Reflexion-style** memory should live as a `:kind :reflection` message type and a small reflection-generation hook, not as a separate backend.
- **Voyager-style** skill memory should be a standalone plugin with its own code library, not forced into `MemoryBackend`.
- **MemGPT/Letta-style** paging should be addressed only if we redesign the context-assembly loop; it is not a backend-only change.

### What to avoid as a direct backend

- Direct Python dependencies (AriGraph, HippoRAG, `compiled-memory`, Letta) in the JVM runtime. Adopt their designs, not their code.
- Vector stores rebranded as "memory" (Chroma, Weaviate, etc.) — out of scope for this catalog.

---

## Suggested next step

Spike a **`:bm25-text` backend** behind the existing `MemoryBackend` protocol:

1. Add `src/kschltz/agent/memory/bm25_text_backend.clj`.
2. Store messages per session in an EDN/JSONL file or SQLite table.
3. Build a small in-memory inverted index on load and update it on store.
4. Implement `-recall-hybrid` as `BM25(query-text, top-y) ∪ last-n`, deduped and sorted.
5. Wire it into `resources/lateralus/config.edn` as an optional `:impl :bm25-text`.
6. Add a test namespace that mirrors `kschltz.agent.memory.proximum-backend-test`.

That gives lateralus-v2 a fully embedding-free memory backend that is portable, native-image-friendly, and interchangeable with the Proximum backend.
