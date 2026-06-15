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

   This plugin is the single source of truth for the default
   exchange chain. Callers and tests can build the same chain with
   `(plugin/assemble-chain [(base-plugin)])`."
  (:require [kschltz.agent.interceptors :as ix]
            [kschltz.agent.plugin :as plugin]))

(defn base-plugin
  "Return the default base plugin map. With no additional plugins,
   `(plugin/assemble-chain [(base-plugin)])` produces a chain that
   is the single source of truth for the default stage order."
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
