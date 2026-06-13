(ns kschltz.agent.memory.embedding
  "Embedder protocol.

   Step 6 wires the real HTTP embedder; the noop embedder returns
   fixed-dimension zero vectors for tests and the default
   Integrant config (so that v2 ships even when no embedder is
   configured).")

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

(defn http-embedder
  "Step 6 placeholder. Throws until HTTP impl ships."
  [_opts]
  (throw (ex-info "http-embedder not yet implemented (Step 6)" {})))
