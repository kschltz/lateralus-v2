(ns kschltz.agent.transitions
  "Staged runtime-state transitions for lateralus agents.

   Interceptors and tools do not mutate the runtime atom. They enqueue
   allowlisted transition ops onto `:agent/transitions`. A commit-stage
   interceptor (see `kschltz.agent.transitions.interceptors`) folds the
   queue into the working `:agent/state` on ctx, accumulates
   `:agent/state-delta` for the outer runtime merge, patches in-flight
   `:llm/request` knobs, and clears the queue.

   This namespace owns the algebra only — no interceptor wiring, no
   Tool protocol, no Integrant keys."
  (:require [cheshire.core :as json]
            [kschltz.agent.tools.factory.protocol :as factory.proto]
            [malli.core :as m]
            [malli.error :as me]
            [malli.instrument :as mi]))

(def llm-config-keys
  "Session LLM knobs that may be rewritten mid-exchange."
  #{:model :base-url :api-key})

(def durable-state-keys
  "Keys written into `:agent/state-delta` from applied transitions.
   `:mcp/servers` is replaced wholesale on merge (see runtime)."
  (into llm-config-keys
        #{:mcp/servers :agent/system-message :agent/loop-opts
          :agent/disabled-tools :agent/memory-policy
          :agent/runtime-reload
          :agent/runtime-tools :agent/promoted-tools}))

(def LoopOptsPatch
  "Allowlisted per-session loop policy fields."
  [:map {:closed true}
   [:max-loop-depth {:optional true} [:int {:min 1}]]
   [:max-tool-calls-per-exchange {:optional true} [:int {:min 1}]]
   [:max-tool-calls-per-turn {:optional true} [:int {:min 1}]]
   [:tool-content-caps {:optional true}
    [:map-of [:string {:min 1}] [:int {:min 1}]]]
   [:tool-schema-mode {:optional true} [:enum :full :compact]]])

(def SetLlmOp
  "Transition that updates allowlisted LLM session config keys.
   At least one of `:model`, `:base-url`, or `:api-key` must be present."
  [:and
   [:map {:closed true}
    [:op [:= :set-llm]]
    [:model {:optional true} [:string {:min 1}]]
    [:base-url {:optional true} [:string {:min 1}]]
    [:api-key {:optional true} [:string {:min 1}]]]
   [:fn {:error/message "set-llm requires at least one of :model, :base-url, :api-key"}
    (fn [op]
      (boolean (some #(contains? op %) [:model :base-url :api-key])))]])

(def McpServerConfigView
  "Redacted / durable MCP server config carried on transitions.
   Open map: control tools already validated the live connect config."
  [:map])

(def McpUpsertServerOp
  [:map {:closed true}
   [:op [:= :mcp-upsert-server]]
   [:server-id [:string {:min 1}]]
   [:config McpServerConfigView]])

(def McpRemoveServerOp
  [:map {:closed true}
   [:op [:= :mcp-remove-server]]
   [:server-id [:string {:min 1}]]])

(def McpRefreshServerOp
  [:map {:closed true}
   [:op [:= :mcp-refresh-server]]
   [:server-id [:string {:min 1}]]])

(def SetSystemMessageOp
  [:map {:closed true}
   [:op [:= :set-system-message]]
   [:message [:string {:min 1}]]])

(def SetLoopOptsOp
  [:and
   [:map {:closed true}
    [:op [:= :set-loop-opts]]
    [:max-loop-depth {:optional true} [:int {:min 1}]]
    [:max-tool-calls-per-exchange {:optional true} [:int {:min 1}]]
    [:max-tool-calls-per-turn {:optional true} [:int {:min 1}]]
    [:tool-content-caps {:optional true}
     [:map-of [:string {:min 1}] [:int {:min 1}]]]]
   [:fn {:error/message "set-loop-opts requires at least one policy field"}
    (fn [op]
      (boolean
       (some #(contains? op %)
             [:max-loop-depth
              :max-tool-calls-per-exchange
              :max-tool-calls-per-turn
              :tool-content-caps])))]])

(def SetToolEnabledOp
  [:map {:closed true}
   [:op [:= :set-tool-enabled]]
   [:tool-name [:string {:min 1}]]
   [:enabled :boolean]])

(def MemoryPolicyPatch
  [:map {:closed true}
   [:top-y {:optional true} [:int {:min 1}]]
   [:last-n {:optional true} [:int {:min 1}]]
   [:recall-enabled {:optional true} :boolean]
   [:persist-enabled {:optional true} :boolean]])

(def SetMemoryPolicyOp
  [:and
   [:map {:closed true}
    [:op [:= :set-memory-policy]]
    [:top-y {:optional true} [:int {:min 1}]]
    [:last-n {:optional true} [:int {:min 1}]]
    [:recall-enabled {:optional true} :boolean]
    [:persist-enabled {:optional true} :boolean]]
   [:fn {:error/message "set-memory-policy requires at least one policy field"}
    (fn [op]
      (boolean
       (some #(contains? op %)
             [:top-y :last-n :recall-enabled :persist-enabled])))]])

(def RuntimeNamespace
  [:re #"^kschltz\.(?:agent(?:\..+)?|lateralus)$"])

(def ReloadRuntimeOp
  [:and
   [:map {:closed true}
    [:op [:= :reload-runtime]]
    [:namespaces {:optional true} [:vector {:min 1} RuntimeNamespace]]
    [:from-edits {:optional true} :boolean]]
   [:fn {:error/message "reload-runtime needs :namespaces or :from-edits true"}
    (fn [op]
      (or (seq (:namespaces op))
          (true? (:from-edits op))))]])

(def RegisterRuntimeToolOp
  [:map {:closed true}
   [:op [:= :register-runtime-tool]]
   [:spec factory.proto/ToolSpec]])

(def ForgetRuntimeToolOp
  [:map {:closed true}
   [:op [:= :forget-runtime-tool]]
   [:tool-name [:string {:min 1}]]])

(def PromoteRuntimeToolOp
  [:map {:closed true}
   [:op [:= :promote-runtime-tool]]
   [:tool-name [:string {:min 1}]]
   [:as-plugin {:optional true} :boolean]
   [:target {:optional true} [:enum :workspace :project]]
   [:workspace-root {:optional true} :string]])

(def Transition
  "Closed union of supported transition ops."
  [:multi {:dispatch :op}
   [:set-llm SetLlmOp]
   [:mcp-upsert-server McpUpsertServerOp]
   [:mcp-remove-server McpRemoveServerOp]
   [:mcp-refresh-server McpRefreshServerOp]
   [:set-system-message SetSystemMessageOp]
   [:set-loop-opts SetLoopOptsOp]
   [:set-tool-enabled SetToolEnabledOp]
   [:set-memory-policy SetMemoryPolicyOp]
   [:reload-runtime ReloadRuntimeOp]
   [:register-runtime-tool RegisterRuntimeToolOp]
   [:forget-runtime-tool ForgetRuntimeToolOp]
   [:promote-runtime-tool PromoteRuntimeToolOp]])

(def Transitions
  [:vector Transition])

(defn valid-transition?
  "True when `op` conforms to `Transition`."
  [op]
  (m/validate Transition op))

(defn explain-transition
  "Humanized Malli explanation for an invalid transition, or nil."
  [op]
  (some-> (m/explain Transition op) me/humanize))

(defn- set-llm-patch
  "Project a `:set-llm` op down to the allowlisted key map."
  [op]
  (select-keys op [:model :base-url :api-key]))

(defn- redact-mcp-config
  [cfg]
  (cond-> (dissoc (or cfg {}) :bearer-token :env :http-fn :__client)
    (contains? cfg :bearer-token) (assoc :bearer-token-set true)
    (contains? cfg :env) (assoc :env-set true
                                :env-keys (vec (sort (map str (keys (:env cfg))))))))

(defn apply-transition
  "Apply one validated `op` to `state`. Returns updated state.
   Unknown ops are ignored (caller should validate first)."
  [state op]
  (case (:op op)
    :set-llm (merge (or state {}) (set-llm-patch op))
    :mcp-upsert-server
    (update (or state {}) :mcp/servers
            (fn [servers]
              (assoc (or servers {})
                     (:server-id op)
                     (redact-mcp-config (:config op)))))
    :mcp-remove-server
    (update (or state {}) :mcp/servers
            (fn [servers]
              (dissoc (or servers {}) (:server-id op))))
    :mcp-refresh-server
    (or state {})
    :set-system-message
    (assoc (or state {}) :agent/system-message (:message op))
    :set-loop-opts
    (update (or state {}) :agent/loop-opts
            (fn [opts]
              (merge (or opts {})
                     (select-keys op
                                  [:max-loop-depth
                                   :max-tool-calls-per-exchange
                                   :max-tool-calls-per-turn
                                   :tool-content-caps
                                   :tool-schema-mode]))))
    :set-tool-enabled
    (update (or state {}) :agent/disabled-tools
            (fn [disabled]
              (let [current (set (or disabled []))
                    updated (if (:enabled op)
                              (disj current (:tool-name op))
                              (conj current (:tool-name op)))]
                (vec (sort updated)))))
    :set-memory-policy
    (update (or state {}) :agent/memory-policy
            (fn [policy]
              (merge (or policy {})
                     (select-keys op
                                  [:top-y :last-n
                                   :recall-enabled :persist-enabled]))))
    :reload-runtime
    (let [nses (or (seq (:namespaces op))
                   (seq (:agent/edited-namespaces (or state {}))))]
      (assoc (or state {})
             :agent/runtime-reload
             {:namespaces (vec (distinct (or nses [])))}))
    :register-runtime-tool
    (update (or state {}) :agent/runtime-tools
            (fn [tools]
              (assoc (or tools {}) (get-in op [:spec :name]) (:spec op))))
    :forget-runtime-tool
    (-> (or state {})
        (update :agent/runtime-tools
                (fn [tools] (dissoc (or tools {}) (:tool-name op))))
        (update :agent/promoted-tools
                (fn [names]
                  (vec (remove #{(:tool-name op)} (or names []))))))
    :promote-runtime-tool
    (-> (or state {})
        (update :agent/runtime-tools
                (fn [tools] (dissoc (or tools {}) (:tool-name op))))
        (update :agent/promoted-tools
                (fn [names]
                  (vec (distinct (conj (or names []) (:tool-name op)))))))
    state))

(defn apply-transitions
  "Fold `ops` left-to-right over `state`. Invalid ops are skipped.
   Returns `{:state s' :applied [op…]}` where `:applied` lists the
   ops that actually contributed (valid only)."
  [state ops]
  (reduce (fn [{:keys [state applied]} op]
            (if (valid-transition? op)
              {:state   (apply-transition state op)
               :applied (conj applied op)}
              {:state state :applied applied}))
          {:state (or state {}) :applied []}
          (or ops [])))

(defn patch-llm-request
  "Return `req` with allowlisted LLM knobs overwritten from `state`.
   Preserves messages and any other request fields. No-op when `req`
   is nil."
  [req state]
  (if (nil? req)
    req
    (merge req (select-keys (or state {}) llm-config-keys))))

(defn durable-delta
  "Project applied transitions + resulting `state` into a state-delta
   patch. `:mcp/servers` is taken from the full post-apply state so
   removals replace the map wholesale."
  [_before after applied]
  (let [llm-patch (apply merge {}
                         (map (fn [op]
                                (when (= :set-llm (:op op))
                                  (select-keys op llm-config-keys)))
                              applied))
        mcp-touched? (some (fn [op]
                             (contains? #{:mcp-upsert-server
                                          :mcp-remove-server}
                                        (:op op)))
                           applied)
        system-message-touched? (some #(= :set-system-message (:op %)) applied)
        loop-opts-touched? (some #(= :set-loop-opts (:op %)) applied)
        tools-touched? (some #(= :set-tool-enabled (:op %)) applied)
        memory-touched? (some #(= :set-memory-policy (:op %)) applied)
        reload-touched? (some #(= :reload-runtime (:op %)) applied)
        runtime-tools-touched?
        (some #(contains? #{:register-runtime-tool
                            :forget-runtime-tool
                            :promote-runtime-tool}
                          (:op %))
              applied)]
    (cond-> llm-patch
      mcp-touched?
      (assoc :mcp/servers (or (:mcp/servers after) {}))
      system-message-touched?
      (assoc :agent/system-message (:agent/system-message after))
      loop-opts-touched?
      (assoc :agent/loop-opts (:agent/loop-opts after))
      tools-touched?
      (assoc :agent/disabled-tools (or (:agent/disabled-tools after) []))
      memory-touched?
      (assoc :agent/memory-policy (:agent/memory-policy after))
      reload-touched?
      (assoc :agent/runtime-reload (:agent/runtime-reload after))
      runtime-tools-touched?
      (assoc :agent/runtime-tools (or (:agent/runtime-tools after) {})
             :agent/promoted-tools (or (:agent/promoted-tools after) [])))))

(defn redact-transition
  "Return a logging/model-safe copy of `op` with secrets replaced by
   boolean markers when present."
  [op]
  (case (:op op)
    :set-llm
    (cond-> (dissoc op :api-key)
      (contains? op :api-key) (assoc :api-key-set true))
    :mcp-upsert-server
    (update op :config redact-mcp-config)
    op))

(defn transition-envelope?
  "True when a parsed tool-result map carries a `:transition` key."
  [parsed]
  (and (map? parsed) (contains? parsed :transition)))

(defn parse-tool-result
  "Parse a tool result string as JSON (keywordized). Returns nil on
   non-string or non-JSON input."
  [result]
  (when (string? result)
    (try (json/parse-string result true)
         (catch Throwable _ nil))))

(defn extract-transition
  "Pull a validated transition from a parsed tool-result map.
   Returns the transition map or nil when absent/invalid."
  [parsed]
  (when (transition-envelope? parsed)
    (let [op (:transition parsed)]
      (when (valid-transition? op) op))))

(defn model-visible-result
  "Rewrite a tool-result envelope so `:transition` never echoes
   secrets. Used by harvest after enqueueing the real op."
  [parsed]
  (if-not (transition-envelope? parsed)
    parsed
    (update parsed :transition redact-transition)))

(defn encode-result
  "JSON-encode a tool result map (pretty for model readability)."
  [m]
  (json/generate-string m {:pretty true}))

(m/=> valid-transition? [:=> [:cat :any] :boolean])
(m/=> apply-transition [:=> [:cat [:maybe :map] :map] :map])
(m/=> apply-transitions [:=> [:cat [:maybe :map] [:maybe [:sequential :any]]]
                         [:map
                          [:state :map]
                          [:applied [:vector :map]]]])
(m/=> patch-llm-request [:=> [:cat [:maybe :map] [:maybe :map]] [:maybe :map]])
(m/=> extract-transition [:=> [:cat [:maybe :map]] [:maybe :map]])
(m/=> durable-delta [:=> [:cat [:maybe :map] [:maybe :map] [:maybe [:sequential :any]]] :map])

(defn instrument!
  "Instrument this namespace's public fns with Malli."
  []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.transitions)]}))

(instrument!)
