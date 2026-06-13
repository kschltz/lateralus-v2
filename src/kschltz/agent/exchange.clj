(ns kschltz.agent.exchange
  "Default per-exchange interceptor chain assembly.

   This ns is the assembly point for a single exchange
   (user turn + any tool calls + final response). It depends on
   `kschltz.agent.interceptors` and `kschltz.agent.plugin` so
   avoiding the loop ↔ interceptors require cycle that v1 had.

   Order rationale (enter in queue order, leave in stack-reverse):
     1. error-boundary FIRST so it catches anything later
     2. bind-llm-client copies the agent's LlmClient onto ctx so
        llm-call actually sees the Integrant-configured client
     3. compose-context builds the LLM request
     4. llm-call invokes the LlmClient (no direct HTTP here)
     5. parse-response extracts :exchange/response and :tool/calls
     6. dispatch re-enqueues compose/llm/parse while tool calls
        remain (sequential mapv, not pmap)
     7. store-exchange / deliver-responses / notify run in :leave
        order (stack-reverse)

   No business logic here. Only ordering of stage references."
  (:require [kschltz.agent.interceptors :as ix]))

(def default-exchange-chain
  "The default chain of interceptor stages for one exchange.
   Order matters — see ns docstring."
  [ix/error-boundary
   ix/bind-llm-client
   ix/compose-context
   ix/llm-call
   ix/parse-response
   ix/dispatch
   ix/store-exchange
   ix/deliver-responses
   ix/notify])
