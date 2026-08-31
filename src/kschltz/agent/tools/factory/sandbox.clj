(ns kschltz.agent.tools.factory.sandbox
  "Host bridge for SCI-compiled runtime tools.

   Model-authored code never receives the interceptor context or registry.
   During one invocation the host binds them here, and SCI may call only
   explicitly allowlisted host tools by name. Results cross back as strings."
  (:require [kschltz.agent.tool :as tool]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def ^:private always-denied-tools
  #{"clojure_eval"
    "clojure_add_lib"
    "tool_define"
    "tool_promote"
    "tool_forget"
    "mcp_upsert_server"
    "mcp_remove_server"
    "set_llm_config"
    "file_create"
    "file_update"
    "file_write"
    "file_patch"})

(def ^:dynamic *invocation*
  "Host-only invocation data. Never copied into the SCI namespace."
  nil)

(defn call-tool
  "Invoke one explicitly allowlisted host tool from sandboxed code.

   The real ctx remains in this host namespace. SCI receives only the
   returned string, never Tool/session/client objects."
  [tool-name args]
  (let [{:keys [ctx allowed-tools]} *invocation*
        tool-name (str tool-name)]
    (when-not (and (map? *invocation*)
                   (contains? (or allowed-tools #{}) tool-name)
                   (not (contains? always-denied-tools tool-name)))
      (throw (ex-info (str "sandbox call-tool is not allowed for: " tool-name)
                      {:phase :sandbox :tool-name tool-name})))
    (let [target (get (:agent/tool-registry ctx) tool-name)]
      (when-not (tool/tool? target)
        (throw (ex-info (str "sandbox call-tool target is unavailable: "
                             tool-name)
                        {:phase :sandbox :tool-name tool-name})))
      (when (tool/untrusted-runtime-tool? target)
        (throw (ex-info "sandbox call-tool cannot invoke another runtime tool"
                        {:phase :sandbox :tool-name tool-name})))
      (let [result (tool/invoke-tool target args ctx)]
        (if (string? result)
          result
          (throw (ex-info "sandbox host tool returned a non-string result"
                          {:phase :sandbox :tool-name tool-name})))))))

(defn invoke-sandboxed
  "Invoke SCI function `f` with opaque args. The model-authored function gets
   nil for its optional ctx argument; the host ctx is available only to
   [[call-tool]] through a dynamic binding."
  [f args ctx allowed-tools]
  (binding [*invocation* {:ctx ctx
                          :allowed-tools (set (or allowed-tools #{}))}]
    (try
      (f args nil)
      (catch clojure.lang.ArityException two-arity-error
        (try
          (f args)
          (catch clojure.lang.ArityException _
            (throw two-arity-error)))))))

(m/=> call-tool [:=> [:cat :string :map] :string])
(m/=> invoke-sandboxed [:=> [:cat fn? :map [:maybe :map] [:set :string]] :any])

(defn instrument!
  []
  (mi/instrument!
   {:filters [(mi/-filter-ns 'kschltz.agent.tools.factory.sandbox)]}))

(instrument!)
