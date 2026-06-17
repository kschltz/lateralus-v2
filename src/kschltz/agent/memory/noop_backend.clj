(ns kschltz.agent.memory.noop-backend
  "No-op MemoryBackend. The test default for `:lateralus/memory-backend`.
   Stores nothing and recalls `[]`. The `MemoryBackend` protocol is the
   contract; new backends plug in without consumer changes."
  (:require [kschltz.agent.memory.protocol :as protocol]))

(defn backend
  "Construct a no-op backend. Stores nothing; recall returns []."
  []
  (reify protocol/MemoryBackend
    (-store-message [_ _session-id _msg] nil)
    (-recall-hybrid  [_ _session-id _opts] [])
    (-close [_] nil)))
