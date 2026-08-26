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
            [kschltz.agent.tools.mcp.protocol :as mcp-proto]))

(defn live-registry
  "Merge static tool map with the live MCP session registry."
  [static-registry mcp-session]
  (if (mcp-proto/mcp-session? mcp-session)
    (merge (or static-registry {}) (mcp-proto/-registry mcp-session))
    (or static-registry {})))

(defn apply-tool-overlay
  "Remove session-disabled tool names from `registry`."
  [registry state]
  (apply dissoc (or registry {}) (or (:agent/disabled-tools state) [])))

(defn refresh-mcp-tools
  "Re-merge static ∪ session registries onto ctx and patch in-flight
   `:llm/request :tools` so the next LLM call (including ReAct
   follow-ups that skip :compose) sees newly upserted MCP tools.
   No-op when no `:agent/mcp-session` is present."
  [ctx]
  (let [session (:agent/mcp-session ctx)
        static (or (:agent/static-tool-registry ctx) {})
        reg (-> (live-registry static session)
                (apply-tool-overlay (:agent/state ctx)))
        req (:llm/request ctx)]
    (cond-> (assoc ctx :agent/tool-registry reg)
      req (assoc :llm/request
                 (assoc req :tools (mapv tool/tool-definition (vals reg)))))))

(defn- seed-registry-interceptor
  ":guard interceptor that attaches the effective tool registry to
   :agent/tool-registry on the context."
  [registry mcp-session]
  {:name ::seed-registry
   :slot :guard
   :enter (fn [ctx]
            (let [effective (-> (live-registry registry mcp-session)
                                (apply-tool-overlay (:agent/state ctx)))]
              (cond-> (assoc ctx
                             :agent/static-tool-registry (or registry {})
                             :agent/tool-registry effective)
                (seq effective)
                (update :agent/system-append act/merge-system-guidance)
                (mcp-proto/mcp-session? mcp-session)
                (assoc :agent/mcp-session mcp-session))))})

(defn tools-plugin
  "Build a partial plugin that seeds tools on the context.

  Arity-1: registry — map of tool name (string) -> Tool.
  Arity-2: registry + opts map with optional :mcp-session.

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
         session (:mcp-session opts)]
     (with-meta
       [(seed-registry-interceptor registry session)]
       {:plugin/name :tools
        :plugin/rebuild
        (fn []
          (let [fresh-registry
                (if-let [rebuild (-> registry meta :registry/rebuild)]
                  (rebuild)
                  registry)]
            (tools-plugin fresh-registry opts)))}))))
