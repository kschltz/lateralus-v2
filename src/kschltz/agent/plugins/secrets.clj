(ns kschltz.agent.plugins.secrets
  "Secrets plugin for the interceptor chain: use-without-seeing.

   Two interceptors:

     :guard — AFTER `plugins.tools` seeded the registry on ctx, wrap every
              effective Tool. Operator-granted trusted host tools resolve
              selected labels; untrusted runtime tools retain opaque
              handles. Every result is redacted before it can enter
              `:tool/results` / messages.

     :tools — belt-and-braces sweep `:enter` that redacts stored values
              out of `:tool/results`, `:agent/all-tool-results`,
              `:agent/tool-transcript`, `:llm/request :messages`,
              `:exchange/response`, and `:agent/state-delta`.

   Needle sets are recomputed so operator store mutations are reflected
   immediately.

   One model-visible tool IS contributed (operator-turn-offable via
   `:advertise-handle-tool?`): `secret_list_handles`, which returns the
   HANDLE LABELS only — never values — plus a system-guidance hint that
   the store exists and how the {{secret:label}} syntax works. Existing
   secret labels stay invisible until the tool is called. Values remain
   unobtainable: there is no LLM-callable path that returns plaintext."

  (:require [kschltz.agent.secrets :as secrets]))

(def system-guidance
  "Model-facing hint that a secret store exists. Tells the model HOW to
   reference secrets without ever handing it values."
  (str "A secret store is available. Tools that need credentials accept a "
       "{{secret:label}} handle in their arguments: call the \""
       secrets/handles-tool-name "\" tool to list the handles, then pass "
       "the handle (e.g. {{secret:label}}) only to a tool granted that "
       "secret capability by the operator. Runtime-authored tools receive "
       "opaque handles, never values; they must delegate credential use via "
       "lateralus.runtime/call-tool to an allowlisted host tool. "
       "Secret VALUES can never be read or displayed; never ask the user "
       "to paste a secret into the chat."))

(defn- wrap-registry
  "Wrap every tool in `registry` (map name -> Tool) for `store`.
   Value-cached in `cache` so an unchanged registry reuses wrappers
   across exchanges and live-registry refreshes."
  [store capabilities cache registry]
  (if-let [wrapped (get @cache registry)]
    wrapped
    (let [wrapped
          (into {}
                (map (fn [[k t]]
                       [k (secrets/wrap-tool
                           store t (get capabilities (str k)))]))
                registry)]
      (vswap! cache assoc registry wrapped)
      wrapped)))

(defn- append-system-guidance
  [prior]
  (cond
    (string? prior) (str prior "\n\n" system-guidance)
    (sequential? prior) (conj (vec prior) system-guidance)
    :else system-guidance))

(defn- wrap-enter
  [transform-registry advertise-handle-tool?]
  (fn [ctx]
    (let [static (or (:agent/static-tool-registry ctx) {})
          effective (or (:agent/tool-registry ctx) {})]
      (cond-> (assoc ctx
                     ;; Preserve raw static tools so same-exchange refreshes
                     ;; do not wrap an already wrapped registry repeatedly.
                     :agent/raw-static-tool-registry static
                     :agent/static-tool-registry (transform-registry static)
                     :agent/tool-registry (transform-registry effective)
                     ;; Live MCP/factory overlays can change after :guard.
                     ;; The tools plugin reapplies this transform on refresh.
                     :agent/tool-registry-transform transform-registry)
        advertise-handle-tool?
        (update :agent/system-append append-system-guidance)))))

(defn- scrub-messages
  "Redact each message's :content using `pairs`."
  [pairs req]
  (if (map? req)
    (assoc req :messages
           (into []
                 (map (fn [m] (assoc m :content (secrets/redact-string pairs (:content m)))))
                 (:messages req)))
    req))

(defn- redact-enter
  [store]
  (fn [ctx]
    (let [pairs (secrets/collect-redaction-pairs store)]
      (if (seq pairs)
        (let [red (partial secrets/redact-string pairs)
              scrub-messages #(scrub-messages pairs %)]
          (cond-> ctx
            (sequential? (:tool/results ctx))
            (update :tool/results (fn [rs] (mapv #(update % :result red) rs)))

            (sequential? (:agent/all-tool-results ctx))
            (update :agent/all-tool-results (fn [rs] (mapv #(update % :result red) rs)))

            (sequential? (:agent/tool-transcript ctx))
            (update :agent/tool-transcript (fn [ms] (mapv #(update % :content red) ms)))

            (seq (get-in ctx [:llm/request :messages] []))
            (update :llm/request scrub-messages)

            (string? (:exchange/response ctx))
            (update :exchange/response red)

            (some? (:agent/state-delta ctx))
            (update :agent/state-delta
                    (fn [sd] (secrets/redact-data pairs sd)))))
        ctx))))

(defn secrets-plugin
  "Construct the secrets plugin. `opts` keys:
     :store — required `SecretStore` instance."
  [{:keys [store advertise-handle-tool? capabilities]
    :or   {advertise-handle-tool? true}
    :as _opts}]
  {:pre [(satisfies? secrets/SecretStore store)]}
  (let [wrap-cache (volatile! {})
        capabilities (assoc (or capabilities {})
                            secrets/presence-tool-name
                            {:labels :all})
        handle-tool (when advertise-handle-tool?
                      (secrets/wrap-tool store (secrets/handles-tool store)))
        presence-tool (when advertise-handle-tool?
                        (secrets/wrap-tool
                         store (secrets/presence-tool)
                         {:labels :all}))
        transform-registry
        (fn [registry]
          (cond-> (wrap-registry
                   store capabilities wrap-cache (or registry {}))
            handle-tool
            (assoc secrets/handles-tool-name handle-tool)
            presence-tool
            (assoc secrets/presence-tool-name presence-tool)))]
    (with-meta
      [{:name  ::wrap-registry
        :slot  :guard
        :enter (wrap-enter transform-registry advertise-handle-tool?)}
       {:name  ::redact
        :slot  :tools
        :enter (redact-enter store)}]
      {:plugin/name :secrets
       :plugin/rebuild
       (fn [] (secrets-plugin {:store store
                               :advertise-handle-tool? advertise-handle-tool?
                               :capabilities capabilities}))})))