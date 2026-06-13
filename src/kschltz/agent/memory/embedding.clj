(ns kschltz.agent.memory.embedding
  "Embedder protocol.

   The MVP ships a noop embedder (returns [0.0]). A real HTTP
   embedder is a follow-up that satisfies this same protocol —
   when it lands, add the impl as a case in
   `kschltz.agent.system/init-key :lateralus/embedder`.")

(defprotocol Embedder
  (-embed [embedder text]
    "Return a vector of floats representing the embedding of `text`.
     Throws on protocol/network errors.")
  (-dimensions [embedder]
    "Return the dimensionality of the embedding space (constant
     per embedder)."))

(defn noop-embedder
  "Stub embedder. Returns a 1-d zero vector; -dimensions returns 1."
  []
  (reify Embedder
    (-embed [_ _] [0.0])
    (-dimensions [_] 1)))
