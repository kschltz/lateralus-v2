(ns kschltz.agent.transitions.interceptors
  "Interceptor bridge between tool results and staged transitions.

   `harvest-transitions-interceptor` scans `:tool/results` for envelopes
   carrying `:transition`, enqueues validated ops onto
   `:agent/transitions`, and rewrites the model-visible result so
   secrets are never echoed.

   `apply-transitions-interceptor` folds the queue into working
   `:agent/state`, reconciles live `McpSession` I/O for MCP ops, merges
   allowlisted keys into `:agent/state-delta`, patches in-flight
   `:llm/request`, enriches/fails MCP tool results from reconcile
   outcomes, and clears the queue.

   Wire these into the `:tools` slot *between* `dispatch-tools` and
   `compose-tool-results` (harvest then apply), then compose — so
   mid-loop config / MCP changes take effect on the next LLM call and
   composed tool messages reflect reconcile success or failure."
  (:require [kschltz.agent.plugins.tools :as tools.plugin]
            [kschltz.agent.tools.mcp.protocol :as mcp-proto]
            [kschltz.agent.tools.mcp.schemas :as mcp.schemas]
            [kschltz.agent.transitions :as tr]))

(defn- normalize-transition
  "Coerce a JSON-round-tripped transition map into the schema shape
   (`:op` as keyword, `:server-id` as string). Returns nil when `raw`
   is not a map."
  [raw]
  (when (map? raw)
    (let [op (cond-> raw
               (string? (:op raw)) (update :op keyword)
               (keyword? (:server-id raw)) (update :server-id name))]
      (cond-> op
        (and (= :mcp-upsert-server (:op op)) (map? (:config op)))
        (update :config mcp.schemas/normalize-server-config)))))

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

(def ^:private mcp-ops
  #{:mcp-upsert-server :mcp-remove-server :mcp-refresh-server})

(defn- mcp-op?
  [op]
  (contains? mcp-ops (:op op)))

(defn- reserved-names
  "Non-MCP tool names currently on the context registry."
  [ctx session]
  (let [all (set (keys (or (:agent/tool-registry ctx) {})))
        mcp (set (keys (mcp-proto/-registry session)))]
    (into #{} (remove mcp) all)))

(defn- phase-of
  [^Throwable t]
  (let [p (:phase (ex-data t))]
    (if (keyword? p) (name p) "tool")))

(defn- reconcile-mcp-op
  "Run live McpSession I/O for one MCP transition. Returns
   `{:ok true :status m}` or `{:ok false :error :phase :class}`."
  [session ctx op]
  (try
    (when (and (contains? #{:mcp-upsert-server :mcp-remove-server} (:op op))
               (not (mcp-proto/-dynamic-enabled? session)))
      (throw (ex-info
              "Dynamic MCP setup is disabled; set :dynamic {:enabled? true} on :lateralus/mcp-tools"
              {:phase :disabled})))
    (let [status
          (case (:op op)
            :mcp-upsert-server
            (mcp-proto/-upsert-server!
             session
             (:server-id op)
             (:config op)
             {:reserved-names (reserved-names ctx session)})
            :mcp-remove-server
            (mcp-proto/-remove-server! session (:server-id op))
            :mcp-refresh-server
            (mcp-proto/-refresh-server! session (:server-id op))
            nil)]
      {:ok true :status status})
    (catch Throwable t
      {:ok false
       :error (or (ex-message t) (.getName (class t)))
       :phase (phase-of t)
       :class (.getName (class t))})))

(defn- entry-transition
  [entry]
  (some-> entry :result tr/parse-tool-result :transition normalize-transition))

(defn- same-mcp-op?
  [a b]
  (and (map? a) (map? b)
       (= (:op a) (:op b))
       (= (str (:server-id a)) (str (:server-id b)))))

(defn- tool-name-for-op
  [op]
  (case (:op op)
    :mcp-upsert-server "mcp_upsert_server"
    :mcp-remove-server "mcp_remove_server"
    :mcp-refresh-server "mcp_refresh_server"
    "mcp"))

(defn- rewrite-entry-for-outcome
  "Enrich a harvested MCP tool result with reconcile status, or replace
   it with a model-visible error when reconcile failed."
  [entry op outcome]
  (let [parsed (tr/parse-tool-result (:result entry))
        status (:status outcome)]
    (if (:ok outcome)
      (assoc entry
             :result
             (tr/encode-result
              (cond-> (or parsed {})
                true (assoc :ok true
                            :pending "same-exchange"
                            :server-id (:server-id op)
                            :transition (tr/redact-transition op))
                (some? (:tools status)) (assoc :tools (:tools status))
                (some? (:tool-count status)) (assoc :tool-count (:tool-count status))
                (some? (:removed status)) (assoc :removed (:removed status)))))
      (assoc entry
             :result
             (tr/encode-result
              {:ok false
               :tool (or (:tool parsed) (tool-name-for-op op))
               :server-id (:server-id op)
               :error (:error outcome)
               :phase (:phase outcome)
               :class (:class outcome)})))))

(defn- rewrite-results-for-outcomes
  "Rewrite `:tool/results` entries that match reconciled MCP ops."
  [results outcomes]
  (reduce (fn [entries {:keys [op outcome]}]
            (mapv (fn [entry]
                    (let [et (entry-transition entry)]
                      (if (same-mcp-op? et op)
                        (rewrite-entry-for-outcome entry op outcome)
                        entry)))
                  entries))
          (vec (or results []))
          outcomes))

(defn- replace-turn-results
  "Replace the trailing `n` entries of `all` (this turn's results) with
   `rewritten`."
  [all n rewritten]
  (let [all (vec (or all []))
        keep-n (max 0 (- (count all) n))]
    (into (subvec all 0 keep-n) rewritten)))

(defn apply-queued-transitions
  "Apply `:agent/transitions` on `ctx`. Returns updated ctx.

   Non-MCP ops (`:set-llm`) fold into state immediately. MCP ops run
   live `McpSession` reconcile first; only successful reconciles join
   the durable state fold. Tool results for MCP ops are rewritten with
   discovered tools or reconcile errors before compose. Always refreshes
   the MCP tool overlay when a session is present."
  [ctx]
  (let [ops (or (:agent/transitions ctx) [])
        session (:agent/mcp-session ctx)
        has-session? (mcp-proto/mcp-session? session)]
    (if (empty? ops)
      (tools.plugin/refresh-mcp-tools ctx)
      (let [{:keys [applied outcomes]}
            (reduce
             (fn [{:keys [applied outcomes] :as acc} op]
               (if-not (tr/valid-transition? op)
                 acc
                 (if-not (mcp-op? op)
                   {:applied (conj applied op)
                    :outcomes outcomes}
                   (if-not has-session?
                     {:applied applied
                      :outcomes (conj outcomes
                                      {:op op
                                       :outcome {:ok false
                                                 :error "No McpSession on context"
                                                 :phase "tool"
                                                 :class "clojure.lang.ExceptionInfo"}})}
                     (let [outcome (reconcile-mcp-op session ctx op)]
                       {:applied (cond-> applied (:ok outcome) (conj op))
                        :outcomes (conj outcomes {:op op :outcome outcome})})))))
             {:applied [] :outcomes []}
             ops)
            base-state (or (:agent/state ctx) {})
            {:keys [state]} (tr/apply-transitions base-state applied)
            durable (tr/durable-delta base-state state applied)
            results (rewrite-results-for-outcomes (:tool/results ctx) outcomes)
            n (count (or (:tool/results ctx) []))
            ctx' (-> ctx
                     (assoc :agent/state state
                            :agent/transitions []
                            :agent/transitions-applied
                            (mapv tr/redact-transition applied)
                            :tool/results results)
                     (update :agent/all-tool-results
                             replace-turn-results n results)
                     (update :agent/state-delta
                             (fn [d] (merge (or d {}) durable)))
                     (update :llm/request tr/patch-llm-request state))]
        (tools.plugin/refresh-mcp-tools ctx')))))

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
  "`:tools` interceptor — run after harvest and before compose so
   reconcile outcomes land in composed tool messages, and the next LLM
   call (including ReAct follow-ups) sees patched request knobs / tools."
  []
  {:name ::apply-transitions
   :slot :tools
   :enter (fn [ctx] (apply-queued-transitions ctx))})
