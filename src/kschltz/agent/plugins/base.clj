(ns kschltz.agent.plugins.base
  "Default base plugin. It contributes the core interceptors that
   make up the standard exchange chain, expressed as plugin slots so
   that additional plugins (memory, safety, etc.) can be assembled
   around them in a fixed, predictable order.

   Slot assignment matches the default slot order in
   `kschltz.agent.plugin/default-slot-order`:

     :guard    — error-boundary, bind-llm-client
     :compose  — compose-context
     :llm      — llm-call, parse-response
     :dispatch — dispatch
     :history  — store-exchange
     :observe  — deliver-responses
     :notify   — notify

   `exchange/default-exchange-chain` in `kschltz.agent.exchange` is
   kept as a hardcoded var for direct use by tests and callers that
   do not go through the Integrant plugin assembly path. The two
   definitions must stay aligned."
  (:require [kschltz.agent.interceptors :as ix]
            [kschltz.agent.plugin :as plugin]))

(defn base-plugin
  "Return the default base plugin map. With no additional plugins,
   `(plugin/assemble-chain [(base-plugin)])` produces a chain that
   is functionally equivalent to `exchange/default-exchange-chain`."
  []
  {:plugin/name :base
   :plugin/slots
   {:guard    [ix/error-boundary
               ix/bind-llm-client]
    :compose  [ix/compose-context]
    :llm      [ix/llm-call
               ix/parse-response]
    :dispatch [ix/dispatch]
    :history  [ix/store-exchange]
    :observe  [ix/deliver-responses]
    :notify   [ix/notify]}})
