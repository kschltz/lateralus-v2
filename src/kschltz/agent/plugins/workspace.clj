(ns kschltz.agent.plugins.workspace
  "Partial plugin that rebinds workspace-root-bound tools from session state.

   Runs in `:guard` after `plugins.tools` seeds the static registry so
   `:agent/tool-registry-transform` is applied on seed and on every live
   registry refresh (MCP/factory overlays, transition apply)."
  )

(defn- guard-interceptor
  [opts default-root]
  {:name ::workspace-guard
   :slot :guard
   :enter (fn [ctx]
            (assoc ctx
                   :agent/workspace-default-root default-root
                   :agent/workspace-tool-opts opts))})

(defn workspace-plugin
  "Build a partial plugin around workspace tool opts.

   `opts` is a `WorkspaceOpts` map (`:file-tools`, `:clojure-tools`,
   `:default-root`). When empty, returns an empty plugin vector."
  [{:keys [file-tools clojure-tools default-root] :as opts}]
  (let [bundle (cond-> {}
                 (map? file-tools) (assoc :file-tools file-tools)
                 (map? clojure-tools) (assoc :clojure-tools clojure-tools))
        root (or default-root (:workspace-root file-tools) ".")]
    (with-meta
      (if (or (seq file-tools) (seq clojure-tools))
        [(guard-interceptor bundle root)]
        [])
      {:plugin/name :workspace
       :plugin/rebuild (fn [] (workspace-plugin opts))})))
