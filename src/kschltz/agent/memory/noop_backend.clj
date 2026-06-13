(ns kschltz.agent.memory.noop-backend
  "No-op MemoryBackend. Used as the default Integrant component when
   no session storage is configured. Step 6 replaces this with a
   Datalevin v2 implementation that satisfies the same protocol."
  (:require [kschltz.agent.memory.protocol :as protocol]))

(defn backend
  "Construct a no-op backend. Stores nothing; recall returns []."
  []
  (reify protocol/MemoryBackend
    (-store-message [_ _session-id _msg] nil)
    (-recall-hybrid  [_ _session-id _opts] [])
    (-close [_] nil)))
