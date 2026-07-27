(ns kschltz.agent.transitions.interceptors
  "Interceptor bridge between tool results and staged transitions.

   `harvest-transitions-interceptor` scans `:tool/results` for envelopes
   carrying `:transition`, enqueues validated ops onto
   `:agent/transitions`, and rewrites the model-visible result so
   `:api-key` is never echoed.

   `apply-transitions-interceptor` folds the queue into working
   `:agent/state`, merges allowlisted keys into `:agent/state-delta`,
   patches in-flight `:llm/request`, and clears the queue.

   Wire these into the `:tools` slot *between* `dispatch-tools` and
   `compose-tool-results` (harvest) and *after* compose (apply), and
   mirror that order in the ReAct follow-up chain so mid-loop config
   changes take effect on the next LLM call of the same exchange."
  (:require [kschltz.agent.plugins.tools :as tools.plugin]
            [kschltz.agent.transitions :as tr]))

(defn- normalize-transition
  "Coerce a JSON-round-tripped transition map into the schema shape
   (`:op` as keyword, `:server-id` as string). Returns nil when `raw`
   is not a map."
  [raw]
  (when (map? raw)
    (cond-> raw
      (string? (:op raw)) (update :op keyword)
      (keyword? (:server-id raw)) (update :server-id name))))

(defn- harvest-one
  "Process a single `{:call :result}` entry. Returns
   `{:entry e' :transition op-or-nil}`."
  [{:keys [call result] :as entry}]
  (let [parsed (tr/parse-tool-result result)
        op     (some-> parsed :transition normalize-transition)]
    (if (and op (tr/valid-transition? op))
      {:entry      (assoc entry :result (-> parsed
                                            (assoc :transition op)
                                            tr/model-visible-result
                                            tr/encode-result))
       :transition op}
      ;; If the envelope claimed a transition but it was invalid, surface
      ;; a clear error string so the model can correct itself.
      (if (tr/transition-envelope? parsed)
        {:entry {:call call
                 :result (tr/encode-result
                          {:ok false
                           :error "invalid transition"
                           :explain (tr/explain-transition
                                     (normalize-transition (:transition parsed)))})}
         :transition nil}
        {:entry entry :transition nil}))))

(defn harvest-transitions
  "Pure helper: harvest transitions from `results`. Returns
   `{:results rewritten :transitions [op…]}`."
  [results]
  (let [harvested (mapv harvest-one (or results []))]
    {:results     (mapv :entry harvested)
     :transitions (into [] (keep :transition) harvested)}))

(defn apply-queued-transitions
  "Pure helper: apply `:agent/transitions` on `ctx`. Returns updated ctx.
   Always refreshes the MCP tool overlay when a session is present so
   mid-exchange upserts are visible on the next LLM call."
  [ctx]
  (let [ops (or (:agent/transitions ctx) [])
        ctx (if (empty? ops)
              ctx
              (let [base-state (or (:agent/state ctx) {})
                    {:keys [state applied]} (tr/apply-transitions base-state ops)
                    durable (tr/durable-delta base-state state applied)]
                (-> ctx
                    (assoc :agent/state state
                           :agent/transitions []
                           :agent/transitions-applied
                           (mapv tr/redact-transition applied))
                    (update :agent/state-delta
                            (fn [d] (merge (or d {}) durable)))
                    (update :llm/request tr/patch-llm-request state))))]
    (tools.plugin/refresh-mcp-tools ctx)))

(defn- replace-turn-results
  "Replace the trailing `n` entries of `all` (this turn's results, already
   appended by dispatch-tools) with `rewritten` so redaction sticks in
   `:agent/all-tool-results` too."
  [all n rewritten]
  (let [all (vec (or all []))
        keep-n (max 0 (- (count all) n))]
    (into (subvec all 0 keep-n) rewritten)))

(defn harvest-transitions-interceptor
  "`:tools` interceptor — run immediately after `dispatch-tools`."
  []
  {:name ::harvest-transitions
   :slot :tools
   :enter (fn [ctx]
            (let [raw (:tool/results ctx)
                  {:keys [results transitions]} (harvest-transitions raw)
                  n (count (or raw []))]
              (cond-> (-> ctx
                          (assoc :tool/results results)
                          (update :agent/all-tool-results
                                  replace-turn-results n results))
                (seq transitions)
                (update :agent/transitions (fnil into []) transitions))))})

(defn apply-transitions-interceptor
  "`:tools` interceptor — run after `compose-tool-results` so the next
   LLM call (including ReAct follow-ups) sees patched request knobs."
  []
  {:name ::apply-transitions
   :slot :tools
   :enter (fn [ctx] (apply-queued-transitions ctx))})
