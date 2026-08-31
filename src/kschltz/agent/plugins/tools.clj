(ns kschltz.agent.plugins.tools
  "Partial plugin that seeds :agent/tool-registry on the context.

  The base plugin already contains the loop interceptors; this plugin
  only has to place the registry on the context before the :compose
  stage runs. It is a normal partial plugin (no :plugin/complete?
  metadata) so the default base chain is still prepended automatically.

  When an McpSession is supplied, each seed merges static-registry with
  session.registry. The session is also stashed on ctx so transition
  apply can refresh :llm/request :tools mid-ReAct (follow-up chains skip
  :guard/:compose)."
  (:require [kschltz.agent.loop.act :as act]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.factory.protocol :as factory.proto]
            [kschltz.agent.tools.mcp.protocol :as mcp-proto]))

(defn live-registry
  "Merge static tool map with live MCP and factory session registries."
  ([static-registry mcp-session]
   (live-registry static-registry mcp-session nil))
  ([static-registry mcp-session factory-session]
   (cond-> (or static-registry {})
     (mcp-proto/mcp-session? mcp-session)
     (merge (mcp-proto/-registry mcp-session))
     (factory.proto/runtime-tool-store? factory-session)
     (merge (factory.proto/-registry factory-session)))))

(defn apply-tool-overlay
  "Remove session-disabled tool names from `registry`."
  [registry state]
  (apply dissoc (or registry {}) (or (:agent/disabled-tools state) [])))

(defn refresh-live-tools
  "Re-merge static ∪ MCP ∪ factory registries onto ctx and patch
   in-flight `:llm/request :tools` so the next LLM call (including
   ReAct follow-ups that skip :compose) sees newly defined tools.

   Also rehydrates the factory session from `:agent/runtime-tools` so a
   tool_define committed mid-exchange is callable in that SAME exchange
   (previously rehydrate only ran at exchange start, so the promised
   'same turn is ok' silently failed on the first follow-up)."
  [ctx]
  (let [session (:agent/mcp-session ctx)
        factory (:agent/factory-session ctx)
        _ (when (factory.proto/runtime-tool-store? factory)
            (factory.proto/-rehydrate! factory
                                       (get-in ctx [:agent/state
                                                    :agent/runtime-tools])))
        static (or (:agent/raw-static-tool-registry ctx)
                   (:agent/static-tool-registry ctx)
                   {})
        raw-reg (live-registry static session factory)
        transformed-reg (if-let [transform (:agent/tool-registry-transform ctx)]
                          (transform raw-reg)
                          raw-reg)
        reg (apply-tool-overlay transformed-reg (:agent/state ctx))
        req (:llm/request ctx)]
    (cond-> (assoc ctx :agent/tool-registry reg)
      req (assoc :llm/request
                 (assoc req :tools (mapv tool/tool-definition (vals reg)))))))

(defn refresh-mcp-tools
  "Back-compat alias for `refresh-live-tools`."
  [ctx]
  (refresh-live-tools ctx))

(defn- seed-registry-interceptor
  ":guard interceptor that attaches the effective tool registry to
   :agent/tool-registry on the context."
  [registry mcp-session factory-session]
  {:name ::seed-registry
   ;; Plain-data handle so UI/tooling can enumerate the static registry
   ;; without replaying an exchange (see workbench settings HTTP).
   :registry registry
   :slot :guard
   :enter (fn [ctx]
            (when (factory.proto/runtime-tool-store? factory-session)
              (factory.proto/-rehydrate! factory-session
                                         (get-in ctx [:agent/state :agent/runtime-tools])))
            (let [effective (-> (live-registry registry mcp-session factory-session)
                                (apply-tool-overlay (:agent/state ctx)))]
              (cond-> (assoc ctx
                             :agent/static-tool-registry (or registry {})
                             :agent/tool-registry effective)
                (seq effective)
                (update :agent/system-append act/merge-system-guidance)
                (mcp-proto/mcp-session? mcp-session)
                (assoc :agent/mcp-session mcp-session)
                (factory.proto/runtime-tool-store? factory-session)
                (assoc :agent/factory-session factory-session))))})

(defn tools-plugin
  "Build a partial plugin that seeds tools on the context.

  Arity-1: registry — map of tool name (string) -> Tool.
  Arity-2: registry + opts map with optional :mcp-session
  and :factory-session.

  When registry is empty/nil and no session tools exist, the loop
  interceptors in the base plugin become no-ops."
  ([] (tools-plugin {}))
  ([registry]
   (tools-plugin registry nil))
  ([registry opts]
   (let [opts (cond
                (mcp-proto/mcp-session? opts) {:mcp-session opts}
                (map? opts) opts
                :else {})
         session (:mcp-session opts)
         factory (:factory-session opts)]
     (with-meta
       [(seed-registry-interceptor registry session factory)]
       {:plugin/name :tools
        :plugin/rebuild
        (fn []
          (let [fresh-registry
                (if-let [rebuild (-> registry meta :registry/rebuild)]
                  (rebuild)
                  registry)]
            (tools-plugin fresh-registry opts)))}))))
