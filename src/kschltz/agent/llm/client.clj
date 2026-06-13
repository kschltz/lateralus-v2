(ns kschltz.agent.llm.client
  "LlmClient protocol — the boundary between the interceptor engine and
   any actual LLM provider. Step 5 adds the HTTP-backed implementation
   and Malli-instrumented call paths.")

(defprotocol LlmClient
  "Boundary between the interceptor engine and any LLM provider."
  (-call [client req]
    "Invoke the client with `req` (Malli-validated shape). Returns the
     response map. Throws on protocol/network errors."))

(defn stub-client
  "MVP stub LlmClient. Returns a deterministic text response that
   echoes the last user message. Used by tests and the default
   Integrant config."
  []
  (reify LlmClient
    (-call [_client req]
      {:choices [{:message {:role    "assistant"
                            :content (str "lateralus-v2 stub LLM echoed: "
                                          (or (get-in req [:messages last :content])
                                              "<no user text>"))}}]
       :model (or (:model req) "stub/v0")
       :stub? true})))

(defn http-client
  "Step 5 placeholder. Throws until HTTP impl ships."
  [_opts]
  (throw (ex-info "http-client not yet implemented (Step 5)" {})))
