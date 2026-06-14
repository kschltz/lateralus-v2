(ns kschltz.agent.memory.protocol
  "Memory backend protocol.

   MVP ships the no-op impl (`kschltz.agent.memory.noop-backend`).
   A real persistent store — Datalevin, SQLite, LMDB, flat files —
   is a follow-up that satisfies this same protocol; no consumer
   changes required when it lands.

   The protocol is intentionally narrow: storage and recall are the
   only two operations MVP needs (no search-time mutation)."
  (:require [kschltz.agent.memory.embedding :as embedding]))

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

     Each returned message should be consumable by `compose-context`,
     which stringifies it with `(str \"[recall] \" message)`. A real
     backend typically returns maps with `:role`, `:content`,
     `:timestamp`, and `:msg-id`; the noop backend returns [].")
  (-close [backend]
    "Release resources. Called from `ig/halt-key!`."))
