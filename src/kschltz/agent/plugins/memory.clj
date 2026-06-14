(ns kschltz.agent.plugins.memory
  "Memory plugin for the v2 interceptor chain.

   Contributes two slot interceptors:

     :enrich  — pre-compose recall. Runs before `compose-context`,
                reads the configured `MemoryBackend` and sets
                `:memory/recall` on the ctx. With the noop backend
                this is a no-op ([]).

     :persist — leave-stage storage. Runs after the response is
                finalized, stores the user and assistant messages
                via the backend. With the noop backend this is a
                no-op.

   The plugin constructor receives the resolved `MemoryBackend`,
   `Embedder`, and recall options. The interceptors close over them,
   so the runtime does not need to forward those refs onto every
   per-exchange ctx.

   Semantic recall: when an embedder is provided, the user's query
   text is embedded and passed to the backend under `:query-embedding`
   in the recall opts. Backends that do not implement semantic search
   ignore it."
  (:require [kschltz.agent.memory.embedding :as embedding]
            [kschltz.agent.memory.protocol :as mem]))

(defn- now-ms
  "Current time in milliseconds."
  []
  (System/currentTimeMillis))

(defn- recall-enter
  "Build the :enter fn for the recall interceptor."
  [backend embedder top-y last-n]
  (fn [ctx]
    (let [session-id (:exchange/session-id ctx)
          user-text  (:exchange/user-text ctx)
          embedding  (when (and embedder (seq user-text))
                       (embedding/-embed embedder user-text))
          recalled   (if (and backend session-id)
                       (mem/-recall-hybrid backend session-id
                                           {:top-y          top-y
                                            :last-n         last-n
                                            :query-text     user-text
                                            :query-embedding embedding})
                       [])]
      (assoc ctx :memory/recall recalled))))

(defn- persist-leave
  "Build the :leave fn for the persist interceptor."
  [backend]
  (fn [ctx]
    (let [session-id (:exchange/session-id ctx)]
      (when (and backend session-id)
        (when-let [user-text (:exchange/user-text ctx)]
          (mem/-store-message
           backend session-id
           {:role      "user"
            :content   user-text
            :msg-id    (:exchange/user-msg-id ctx)
            :timestamp (now-ms)}))
        (when-let [response (:exchange/response ctx)]
          (mem/-store-message
           backend session-id
           {:role      "assistant"
            :content   response
            :msg-id    (:exchange/assistant-msg-id ctx)
            :timestamp (now-ms)})))
      ctx)))

(defn memory-plugin
  "Construct a memory plugin map.

   `opts` keys:
     :backend  — required `MemoryBackend` instance
     :embedder — optional `Embedder` instance (omit for keyword-only recall)
     :top-y    — number of semantic matches to recall (default 3)
     :last-n   — number of recent messages to recall (default 5)"
  [{:keys [backend embedder top-y last-n]
    :or   {top-y 3 last-n 5}}]
  {:pre [(some? backend)]}
  {:plugin/name :memory
   :plugin/slots
   {:enrich  [{:name  ::recall
               :enter (recall-enter backend embedder top-y last-n)}]
    :persist [{:name  ::persist
               :leave (persist-leave backend)}]}})
