(ns kschltz.agent.plugins.base
  "Default base plugin. It contributes the core interceptors that
   make up the standard exchange chain, expressed as a flat vector so
   that additional plugins (memory, safety, tools, etc.) can be
   assembled around them in a fixed, predictable order.

   Slot assignment matches the default slot order in
   `kschltz.agent.plugin/default-slot-order`:

     :guard    — error-boundary
     :compose  — compose-context, inject-tools
     :llm      — llm-call-with-self-heal, llm-call, parse-response
     :tools    — dispatch-tools, compose-tool-results
     :finalize — tool-loop
     :history  — store-exchange
     :observe  — deliver-responses
     :notify   — notify

   The tool-calling interceptors are always present but are no-ops when
   no `:agent/tool-registry` has been seeded on the context. This makes
   the default chain tool-aware without requiring a complete
   replacement plugin.

   This plugin is the single source of truth for the default exchange
   chain. Callers and tests can build the same chain with
   `(plugin/assemble-chain [(base-plugin)])`."
  (:require [kschltz.agent.interceptors :as ix]
            [kschltz.agent.loop :as loop]
            [kschltz.agent.plugin :as plugin]))

(defn base-plugin
  "Return the default base plugin vector. With no additional plugins,
   `(plugin/assemble-chain [(base-plugin)])` produces a chain that
   is the single source of truth for the default stage order."
  []
  (with-meta
    [(assoc ix/error-boundary :slot :guard)
     (assoc ix/compose-context :slot :compose)
     (assoc (loop/inject-tools-interceptor) :slot :compose)
     (assoc (loop/llm-call-with-self-heal) :slot :llm)
     (assoc ix/llm-call :slot :llm)
     (assoc ix/parse-response :slot :llm)
     (assoc (loop/dispatch-tools-interceptor) :slot :tools)
     (assoc (loop/compose-tool-results-interceptor) :slot :tools)
     (assoc (loop/tool-loop-interceptor (loop/react-loop)) :slot :finalize)
     (assoc ix/store-exchange :slot :history)
     (assoc ix/deliver-responses :slot :observe)
     (assoc ix/notify :slot :notify)]
    {:plugin/name :base}))