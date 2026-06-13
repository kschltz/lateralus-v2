(ns kschltz.agent.memory.noop-backend
  "No-op MemoryBackend. The MVP default for `:lateralus/memory-backend`.
   The `MemoryBackend` protocol is the contract; a real persistent
   store (Datalevin, SQLite, LMDB, flat files, etc.) is a follow-up
   that satisfies the same protocol — no consumer changes required
   when it lands."
  (:require [kschltz.agent.memory.protocol :as protocol]))

(defn backend
  "Construct a no-op backend. Stores nothing; recall returns []."
  []
  (reify protocol/MemoryBackend
    (-store-message [_ _session-id _msg] nil)
    (-recall-hybrid  [_ _session-id _opts] [])
    (-close [_] nil)))
