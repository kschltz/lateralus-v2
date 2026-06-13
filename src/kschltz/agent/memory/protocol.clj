(ns kschltz.agent.memory.protocol
  "Memory backend protocol.

   Step 6 replaces the no-op implementation with a Datalevin v2 store
   that satisfies the same protocol.

   The protocol is intentionally narrow: storage and recall are the
   only two operations MVP needs (no search-time mutation)."
  (:require [kschltz.agent.memory.embedding :as embedding]))

(defprotocol MemoryBackend
  (-store-message [backend session-id msg]
    "Persist a chat message. Returns the backend (for chaining).")
  (-recall-hybrid [backend session-id {:keys [top-y last-n]}]
    "Return a vector of messages for the session, hybrid-recalled:
     top-Y semantic + last-N recent, deduped, chronologically sorted.")
  (-close [backend]
    "Release resources. Called from `ig/halt-key!`."))
