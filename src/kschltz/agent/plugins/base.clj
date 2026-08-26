(ns kschltz.agent.plugins.base
  "Default base plugin. It contributes the core interceptors that
   make up the standard exchange chain, expressed as a flat vector so
   that additional plugins (memory, safety, tools, etc.) can be
   assembled around them in a fixed, predictable order.

   Slot assignment matches the default slot order in
   `kschltz.agent.plugin/default-slot-order`:

     :guard             — error-boundary
     :compose           — compose-context, inject-tools
     :llm               — llm-call-with-self-heal, llm-call, parse-response
     :tools             — dispatch-tools, harvest-transitions,
                          apply-transitions, compose-tool-results
     :finalize          — tool-loop, ensure-text-response
     :history-summarize — summarize-history-interceptor (long-context compaction)
     :history           — store-exchange
     :observe           — deliver-responses
     :notify            — notify

   The tool-calling interceptors are always present but are no-ops when
   no `:agent/tool-registry` has been seeded on the context. This makes
   the default chain tool-aware without requiring a complete
   replacement plugin. Transition harvest/apply are similarly inert
   when no tool enqueued a `:transition` envelope.

   This plugin is the single source of truth for the default exchange
   chain. Callers and tests can build the same chain with
   `(plugin/assemble-chain [(base-plugin)])`."
  (:require [kschltz.agent.interceptors :as ix]
            [kschltz.agent.logging :as logging]
            [kschltz.agent.loop :as loop]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.runtime-reload :as runtime-reload]
            [kschltz.agent.transitions.interceptors :as tr.ix]))

(defn base-plugin
  "Return the default base plugin vector. With no additional plugins,
   `(plugin/assemble-chain [(base-plugin)])` produces a chain that
   is the single source of truth for the default stage order. The
   `tool-loop` and `ensure-text-response` interceptors share one
   `ReActLoop` instance so `ensure-text-response` can ask the same
   loop whether it is still continuing."
  []
  (let [react-loop (loop/react-loop)]
    (with-meta
      [       (assoc (logging/logging-interceptor) :slot :guard)
       (assoc ix/error-boundary :slot :guard)
       (assoc (runtime-reload/notice-interceptor) :slot :enrich)
       (assoc ix/compose-context :slot :compose)
       (assoc (loop/inject-tools-interceptor) :slot :compose)
       (assoc (loop/llm-call-with-self-heal) :slot :llm)
       (assoc ix/llm-call :slot :llm)
       (assoc ix/parse-response :slot :llm)
       (assoc (loop/dispatch-tools-interceptor) :slot :tools)
       (assoc (tr.ix/harvest-transitions-interceptor) :slot :tools)
       (assoc (tr.ix/apply-transitions-interceptor) :slot :tools)
       (assoc (loop/compose-tool-results-interceptor) :slot :tools)
       (assoc (loop/tool-loop-interceptor react-loop) :slot :finalize)
       (assoc (loop/ensure-text-response-interceptor react-loop) :slot :finalize)
       (assoc ix/store-exchange :slot :history)
       (assoc ix/summarize-history-interceptor :slot :history-summarize)
       (assoc ix/deliver-responses :slot :observe)
       (assoc ix/notify :slot :notify)]
      {:plugin/name :base
       :plugin/rebuild (fn [] (base-plugin))})))