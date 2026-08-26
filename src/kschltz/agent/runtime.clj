(ns kschltz.agent.runtime
  "Agent outer loop. Synchronous, single-threaded runtime that builds
   per-exchange ctx, runs the chain, and merges `:agent/state-delta`
   back into its state atom. Also accumulates token usage from LLM
   responses."
  (:require [kschltz.agent.chain :as chain]
            [kschltz.agent.logging :as logging]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.loop.stall :as stall]
            [kschltz.agent.runtime-reload :as runtime-reload]))

(def ^:private default-exchange-chain
  (plugin/assemble-chain [(plugins.base/base-plugin)]))

(def ^:private replace-map-keys
  "Keys whose map values are replaced wholesale on state merge (not
   deep-merged). Needed so `:mcp/servers` removals actually drop keys."
  #{:mcp/servers})

(defn- deep-merge [a b]
  (cond
    (and (map? a) (map? b)) (merge-with deep-merge a b)
    ;; Vectors in state (message lists, history) are values, not
    ;; append-only collections, so last-write-wins keeps deltas from
    ;; concatenating onto base state.
    :else b))

(defn- merge-state [base-state delta]
  (reduce-kv
   (fn [acc k v]
     (if (contains? replace-map-keys k)
       (assoc acc k v)
       (assoc acc k (deep-merge (get acc k) v))))
   (or base-state {})
   (or delta {})))

(defn- usage-delta [base-state response]
  (let [usage   (get response :usage)
        current (get base-state :agent/token-usage
                     {:prompt_tokens 0 :completion_tokens 0 :total_tokens 0})
        updated (if usage
                  (-> current
                      (update :prompt_tokens (fnil + 0) (or (:prompt_tokens usage) 0))
                      (update :completion_tokens (fnil + 0) (or (:completion_tokens usage) 0))
                      (update :total_tokens (fnil + 0) (or (:total_tokens usage) 0)))
                  current)]
    {:agent/token-usage updated}))

(defprotocol AgentRuntime
  "Thin outer-loop runtime contract."
  (session-id [runtime] "Stable ID for the lifetime of this runtime.")
  (send-message [runtime user-text] "Run one exchange.")
  (stop [runtime] "Return the current merged state."))

(defrecord RuntimeRecord [state agent-map session-id log-sink chain]
  AgentRuntime
  (session-id [_] session-id)
  (send-message [this user-text]
    (let [user-msg-id      (str (random-uuid))
          assistant-msg-id (str (random-uuid))
          base-state       @(:state this)
          chain-to-run     @chain
          ctx              {:exchange/session-id       session-id
                            :exchange/user-msg-id      user-msg-id
                            :exchange/assistant-msg-id assistant-msg-id
                            :exchange/user-text        user-text
                            :agent/state               base-state
                            :agent/agent-map           agent-map
                            :agent/exchange-chain      chain-to-run
                            :llm/client               (:agent/llm-client agent-map)
                            :memory/backend           (:memory-backend agent-map)
                            :embedder                 (:embedder agent-map)
                            :agent/log-sink           log-sink
                            :agent/loop-opts          (merge
                                                       (or (:agent/loop-opts agent-map) {})
                                                       (or (:agent/loop-opts base-state) {}))}
          ctx              (stall/seed-from-state ctx)
          result           (chain/execute ctx chain-to-run)
          delta            (:agent/state-delta result)
          merged           (merge-state base-state
                                        (merge-state delta
                                                     (usage-delta base-state (:llm/response result))))]
      (reset! (:state this) merged)
      (when-let [request (:agent/runtime-reload merged)]
        (runtime-reload/apply! this request))
      (cond-> result
        (:agent/runtime-reload-status @(:state this))
        (assoc :agent/runtime-reload-status
               (:agent/runtime-reload-status @(:state this))))))
  (stop [_] @state))

(defn start
  "Create a runtime for the given agent-map. 1-arity generates a fresh
   session-id; 2-arity uses the supplied session-id. Builds and opens a
   per-session log sink from the agent-map's `:agent/logging` config so
   the logging interceptor (first in the chain) can write per-stage
   events. When logging is disabled the sink is nil and logging is inert."
  ([agent-map]
   (start agent-map (str (random-uuid))))
  ([agent-map session-id]
   (let [log-sink (logging/build-sink (:agent/logging agent-map) session-id)
         initial-chain (get agent-map :exchange-chain default-exchange-chain)]
     (map->RuntimeRecord
      {:state      (atom (merge (:initial-state agent-map {})
                                {:agent/session-id session-id}))
       :agent-map  agent-map
       :session-id session-id
       :log-sink   log-sink
       :chain      (atom initial-chain)}))))
