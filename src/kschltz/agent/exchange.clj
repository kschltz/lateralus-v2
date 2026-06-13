(ns kschltz.agent.exchange
  "Default per-exchange interceptor chain assembly.

   This ns is the assembly point for a single exchange
   (user turn + any tool calls + final response). It depends on
   `kschltz.agent.interceptors` and `kschltz.agent.plugin` so
   avoiding the loop ↔ interceptors require cycle that v1 had.

   Order rationale (enter in queue order, leave in stack-reverse):
     1. error-boundary FIRST so it catches anything later
     2. compose-context builds the LLM request
     3. llm-call invokes the LlmClient (no direct HTTP here)
     4. parse-response extracts :exchange/response and :tool/calls
     5. dispatch re-enqueues compose/llm/parse while tool calls
        remain (sequential mapv, not pmap)
     6. store-exchange / deliver-responses / notify run in :leave
        order (stack-reverse)

   No business logic here. Only ordering of stage references."
  (:require [kschltz.agent.interceptors :as ix]))

(def default-exchange-chain
  "The default chain of interceptor stages for one exchange.
   Order matters — see ns docstring."
  [ix/error-boundary
   ix/compose-context
   ix/llm-call
   ix/parse-response
   ix/dispatch
   ix/store-exchange
   ix/deliver-responses
   ix/notify])
