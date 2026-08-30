(ns kschltz.agent.plugins.secrets
  "Secrets plugin for the interceptor chain: use-without-seeing.

   Two interceptors:

     :guard — AFTER `plugins.tools` seeded the registry on ctx (plugin
              declaration order, same slot), wrap every static Tool in
              the registry with `secrets/wrap-tool`: model-supplied
              `{{secret:label}}` handles resolve to plaintext only
              inside `-invoke`, and tool result strings are redacted
              before they can enter `:tool/results` / messages.

     :tools — belt-and-braces sweep `:enter` that redacts stored values
              out of `:tool/results`, `:agent/all-tool-results`,
              `:agent/tool-transcript`, `:llm/request :messages`,
              `:exchange/response`, and `:agent/state-delta`. Catches
              live (MCP/factory) tools that the :guard wrapper cannot
              reach and anything a tool echoed into the follow-up
              request.

   The needle set is computed lazily per exchange and cached; set the
   plugin rebuild hook to pick up store mutations mid-session (runtime
   reload does this for other plugins too).

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
       "the handle (e.g. {{secret:label}}) where a credential is required. "
       "Secret VALUES can never be read or displayed; never ask the user "
       "to paste a secret into the chat."))

(defn- wrap-registry
  "Wrap every tool in `registry` (map name -> Tool) for `store`.
   Value-cached in `cache` so an unchanged registry reuses wrappers
   across exchanges and live-registry refreshes."
  [store cache registry]
  (if-let [wrapped (get @cache registry)]
    wrapped
    (let [wrapped (into {} (map (fn [[k t]] [k (secrets/wrap-tool store t)])) registry)]
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

(defn- redact-needle-pairs
  [store cache]
  (when-not @cache
    (vreset! cache (secrets/collect-redaction-pairs store)))
  @cache)

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
  [store needle-cache]
  (fn [ctx]
    (let [pairs (redact-needle-pairs store needle-cache)]
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
  [{:keys [store advertise-handle-tool?]
    :or   {advertise-handle-tool? true}
    :as _opts}]
  {:pre [(satisfies? secrets/SecretStore store)]}
  (let [wrap-cache (volatile! {})
        needle-cache (volatile! nil)
        handle-tool (when advertise-handle-tool?
                      (secrets/wrap-tool store (secrets/handles-tool store)))
        transform-registry
        (fn [registry]
          (cond-> (wrap-registry store wrap-cache (or registry {}))
            handle-tool
            (assoc secrets/handles-tool-name handle-tool)))]
    (with-meta
      [{:name  ::wrap-registry
        :slot  :guard
        :enter (wrap-enter transform-registry advertise-handle-tool?)}
       {:name  ::redact
        :slot  :tools
        :enter (redact-enter store needle-cache)}]
      {:plugin/name :secrets
       :plugin/rebuild
       (fn [] (secrets-plugin {:store store
                               :advertise-handle-tool? advertise-handle-tool?}))})))