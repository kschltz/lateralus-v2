(ns kschltz.agent.plugins.summarizer
  "History-summarizer plugin.

   The plugin contributes a single `:history-summarize` `:leave`
   interceptor (`kschltz.agent.interceptors/summarize-history`)
   that compacts a long `:agent/history` into a single
   `[Conversation Summary - generated <ts>]` system message plus
   the most-recent `protected-pairs` turn pairs, once the body grows
   past `summarize-trigger`.

   With the noop default (no `:llm-client` supplied), the summary
   text is the placeholder `[summary unavailable]`; the marker and
   the protected window still emit, so the boundary is observable.

   `summarizer-plugin` accepts a map:

     :llm-client      — optional `LlmClient` instance (the runtime
                        also pre-wires the same client onto ctx as
                        `:llm/client`, which the interceptor reads
                        first; passing it explicitly via opts is the
                        unit-test path).
     :trigger         — non-system message count above which to fire
                        (default `summarize-trigger`)
     :protected-pairs — number of user turns to keep verbatim
                        (default `protected-turn-pairs`)
     :model           — model hint passed to the LlmClient
                        (default \"stub-summarizer\")"
  (:require [kschltz.agent.interceptors :as ix]))

(defn summarizer-plugin
  "Build the summarizer plugin vector.

   `opts` keys (see ns docstring for defaults)."
  [{:keys [llm-client trigger protected-pairs model]
    :or   {trigger         ix/summarize-trigger
           protected-pairs ix/protected-turn-pairs
           model           "stub-summarizer"}}]
  (with-meta
    [(assoc (ix/summarize-history {:llm-client      llm-client
                                   :trigger         trigger
                                   :protected-pairs protected-pairs
                                   :model           model})
       :name ::ix/summarize-history
       :slot :history-summarize)]
    {:plugin/name :summarizer}))