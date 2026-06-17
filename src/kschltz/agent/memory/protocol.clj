(ns kschltz.agent.memory.protocol
  "Memory backend protocol.

   Implementations ship in this namespace tree:
     - `kschltz.agent.memory.noop-backend`   — test default; returns []
     - `kschltz.agent.memory.proximum-backend` — JVM HNSW vector store
     - `kschltz.agent.memory.kg-bm25`        — pure-Clojure BM25 + KG

   A new backend satisfies this protocol and plugs into
   `:lateralus/memory-backend` without consumer changes.

   The protocol is intentionally narrow: storage, recall, and close.")

(defprotocol MemoryBackend
  (-store-message [backend session-id msg]
    "Persist a chat message. Returns the backend (for chaining).")
  (-recall-hybrid [backend session-id {:keys [top-y last-n] :as opts}]
    "Return a vector of messages for the session, hybrid-recalled:
     top-Y semantic + last-N recent, deduped, chronologically sorted.

     `opts` is a map that must contain `:top-y` and `:last-n`. It may
     also contain `:query-text` and/or `:query-embedding` to guide
     semantic recall; backends that do not implement semantic search
     can ignore those keys.

     Each returned message is typically a map with `:role`, `:content`,
     `:timestamp`, and `:msg-id`; `compose-context` extracts `:content`
     and prefixes it with `[recall] `. The noop backend returns [].")
  (-close [backend]
    "Release resources. Called from `ig/halt-key!`."))
