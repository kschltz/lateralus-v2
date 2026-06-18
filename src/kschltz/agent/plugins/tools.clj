(ns kschltz.agent.plugins.tools
  "Partial plugin that seeds `:agent/tool-registry` on the context.

   The base plugin already contains the loop interceptors; this plugin
   only has to place the registry on the context before the `:compose`
   stage runs. It is a normal partial plugin (no `:plugin/complete?`
   metadata) so the default base chain is still prepended automatically.")

(defn- seed-registry-interceptor
  "`:guard` interceptor that attaches `registry` (map of name -> Tool) to
   `:agent/tool-registry` on the context. Runs before every stage so the
   loop interceptors in the base plugin can read it."
  [registry]
  {:name ::seed-registry
   :slot :guard
   :enter (fn [ctx]
            (assoc ctx :agent/tool-registry (or registry {})))})

(defn tools-plugin
  "Build a partial plugin that seeds `registry` on the context.

   `registry` is a map of tool name (string) -> Tool implementation.
   When `registry` is empty or nil, the loop interceptors in the base
   plugin become no-ops and the agent behaves as before."
  ([] (tools-plugin {}))
  ([registry]
   (with-meta
     [(seed-registry-interceptor registry)]
     {:plugin/name :tools})))
