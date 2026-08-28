(ns kschltz.agent.workbench.settings-http
  "Runtime settings API for the workbench UI.

   Exposes the same allowlisted transitions the `config` tool group uses
   (`set_llm_config`, `set_system_message`, `set_loop_policy`,
   `set_tool_enabled`, `set_memory_policy`) as a direct HTTP surface so
   the user can edit runtime knobs from a collapsible settings menu —
   no LLM round-trip required.

   Writes go through `kschltz.agent.transitions/apply-transition` onto
   the runtime state atom (the same algebra the chain commits), never
   ad-hoc mutation. Secrets are never returned: only `:api-key-set`."
  (:require [clojure.string :as str]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.config.catalog :as catalog]
            [kschltz.agent.transitions :as tr]
            [kschltz.agent.workbench.hub :as hub]))

(def ^:private editable-ops
  "Transition ops the settings menu may apply. Mirrors the `config`
   tool group; MCP ops need a live session and stay LLM-only."
  #{:set-llm :set-system-message :set-loop-opts
    :set-tool-enabled :set-memory-policy})

(defn- busy?
  [hub]
  (contains? #{:queued :running} (:status (hub/snapshot hub))))

(defn- static-registry
  "Static tool registry captured by the tools plugin's seed interceptor."
  [runtime]
  (some->> (get-in runtime [:agent-map :agent/plugins])
           (filter vector?)
           (mapcat identity)
           (some :registry)))

(defn- llm-view
  [state]
  (cond-> {}
    (:model state)    (assoc :model (:model state))
    (:base-url state) (assoc :base-url (:base-url state))
    (seq (:api-key state)) (assoc :api-key-set true)))

(def ^:private protected-tools
  "Same recovery tools the config tools refuse to disable."
  #{"set_tool_enabled" "runtime_describe" "reload_runtime"
    "tool_define" "tool_promote" "tool_list_runtime" "tool_forget"})

(defn settings-view
  "Current editable settings, safe to send to the browser."
  [runtime]
  (let [state @(:state runtime)
        reg   (static-registry runtime)
        disabled (set (:agent/disabled-tools state))]
    {:session-id    (or (:agent/session-id state) (:session-id runtime))
     :llm           (llm-view state)
     :system-message (or (:agent/system-message state) "")
     :loop-opts     (or (:agent/loop-opts state) {})
     :memory-policy (or (:agent/memory-policy state) {})
     :tools         (vec (for [[name* t] (sort-by key (or reg {}))]
                           {:name      name*
                            :enabled   (not (contains? disabled name*))
                            :protected (contains? protected-tools name*)
                            :description (some-> (tool/-description t)
                                                 str/trim
                                                 (str/split #"\n")
                                                 first)}))}))

(defn apply-op!
  "Validate and apply one transition op to the runtime state.
   Returns {:ok true} or {:ok false :error …}."
  [hub runtime op]
  (cond
    (busy? hub)
    {:ok false :error "a turn is running — try again when idle"}

    (not (map? op))
    {:ok false :error "settings op must be a JSON object"}

    (not (contains? editable-ops (:op op)))
    {:ok false :error (str "unsupported settings op: " (pr-str (:op op)))}

    (not (tr/valid-transition? op))
    {:ok false :error (pr-str (tr/explain-transition op))}

    :else
    (do (swap! (:state runtime) tr/apply-transition op)
        {:ok true :op (:op op)})))

(defn list-models
  "Model ids for `base-url` (falls back to the session's base-url).
   Never returns the api-key."
  [runtime {:keys [base-url api-key]}]
  (let [state  @(:state runtime)
        url    (or (not-empty base-url) (:base-url state))
        key    (or (not-empty api-key) (:api-key state))]
    (if (str/blank? (str url))
      {:models [] :error "no base-url configured"}
      {:models (try
                 (let [catalog (catalog/http-catalog)]
                   (vec (catalog/list-models catalog {:base-url url :api-key key})))
                 (catch Throwable t
                   {:error (ex-message t)}))})))