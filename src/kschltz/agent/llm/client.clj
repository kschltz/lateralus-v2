(ns kschltz.agent.llm.client
  "LlmClient protocol — the boundary between the interceptor engine and
   any actual LLM provider.

   Two implementations:
     - `stub-client` — MVP default. Echoes the last user message.
     - `http-client` — real OpenAI-shaped HTTP backend. See
       `kschltz.agent.llm.http` and `kschltz.agent.llm.schemas`.

   The protocol boundary is the only contract consumers depend
   on; both impls satisfy it. Switching the Integrant config
   from :impl :stub to :impl :http swaps the wired LlmClient
   with no other code change.")

(defprotocol LlmClient
  "Boundary between the interceptor engine and any LLM provider."
  (-call [client req]
    "Invoke the client with `req` (Malli-validated shape). Returns the
     response map. Throws on protocol/network errors."))

(defn stub-client
  "MVP default LlmClient. Returns a deterministic text response that
   echoes the last user message. Used by tests and the default
   Integrant config."
  []
  (reify LlmClient
    (-call [_client req]
      {:choices [{:message {:role    "assistant"
                            :content (str "lateralus-v2 stub LLM echoed: "
                                          (or (some-> req :messages last :content)
                                              "<no user text>"))}}]
       :model (or (:model req) "stub/v0")
       :stub? true})))

(defn http-client
  "Construct a real OpenAI-shaped LlmClient. Delegates to
   `kschltz.agent.llm.http/http-client`; this thin wrapper exists
   so consumers can `require` `kschltz.agent.llm.client` and stay
   out of the HTTP-specific namespace."
  [opts]
  ((requiring-resolve 'kschltz.agent.llm.http/http-client) opts))
