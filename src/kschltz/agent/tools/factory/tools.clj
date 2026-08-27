(ns kschltz.agent.tools.factory.tools
  "Control tools for runtime tool define / forget / list / promote.

   Mutating tools propose closed transitions. Compile, registry overlay,
   and disk promotion run in the transitions apply interceptor."
  (:require [clojure.string :as str]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.factory.protocol :as proto]
            [kschltz.agent.transitions :as tr]
            [malli.core :as m]
            [malli.error :as me]
            [malli.instrument :as mi]))

(def DefineInput
  "Model-facing input. Closed ToolSpec is enforced after coerce — local
   models send snake_case keys and sometimes a data schema, not EDN."
  [:map
   [:name proto/portable-tool-name]
   [:description [:string {:min 1}]]
   [:input-schema {:optional true} :any]
   [:invoke {:optional true} :any]])

(defn- kebab-key
  [k]
  (keyword (str/replace (name k) #"_" "-")))

(defn normalize-tool-spec
  "Accept the shapes models actually emit; persist a closed ToolSpec."
  [spec]
  (let [spec (into {} (map (fn [[k v]] [(kebab-key k) v]) (or spec {})))]
    (cond-> spec
      (and (some? (:input-schema spec)) (not (string? (:input-schema spec))))
      (update :input-schema pr-str)
      (and (some? (:invoke spec)) (not (string? (:invoke spec))))
      (update :invoke pr-str)
      (and (some? (:libs spec)) (not (string? (:libs spec))))
      (update :libs pr-str)
      (string? (:interceptor-slot spec))
      (update :interceptor-slot keyword))))

(def ToolNameInput
  [:map {:closed true}
   [:name proto/portable-tool-name]])

(def ListInput
  [:map {:closed true}])

(def PromoteInput
  [:map {:closed true}
   [:name proto/portable-tool-name]
   [:as-plugin {:optional true} :boolean]
   [:target {:optional true} [:enum "workspace" "project" :workspace :project]]])

(defn- coerce-target
  [target]
  (when target
    (keyword (name target))))

(defrecord ToolDefineTool [session]
  tool/Tool
  (-name [_] "tool_define")
  (-description [_]
    "Create a callable session tool now: name, description, input-schema (EDN Malli string), invoke (Clojure string of (fn [args ctx] result)). Do not use clojure_eval. You may call the new name in this same turn (parallel with tool_define) or on the next LLM call this exchange. For live HTTP prefer java.net.URL + slurp (set User-Agent) or an existing web_* tool — do not add clj-http. Optional libs/require/alias and interceptor-slot plus interceptor-enter/leave/error. Emits a transition; compile + registry refresh happen before compose.")
  (-input-schema [_] DefineInput)
  (-output-schema [_] :string)
  (-invoke [_ spec _ctx]
    (if-not (proto/runtime-tool-store? session)
      (tr/encode-result {:ok false :tool "tool_define" :error "No factory session on context"})
      (if-not (proto/-dynamic-enabled? session)
        (tr/encode-result {:ok false :tool "tool_define"
                           :error "Dynamic tool factory is disabled"
                           :phase "disabled"})
        (let [spec (normalize-tool-spec spec)]
          (if-not (proto/valid-tool-spec? spec)
            (tr/encode-result
             {:ok false
              :tool "tool_define"
              :phase "compile"
              :error (str "invalid tool spec: "
                          (pr-str (some-> (m/explain proto/ToolSpec spec)
                                          me/humanize)))})
            (let [before (proto/-status session)]
              (tr/encode-result
               {:ok true
                :tool "tool_define"
                :pending "same-exchange"
                :tool-name (:name spec)
                :before before
                :transition {:op :register-runtime-tool :spec spec}}))))))))

(defrecord ToolForgetTool [session]
  tool/Tool
  (-name [_] "tool_forget")
  (-description [_]
    "Remove a runtime-defined tool (ephemeral or session-promoted overlay) so later turns cannot call it. Built-in Integrant tools are not removed.")
  (-input-schema [_] ToolNameInput)
  (-output-schema [_] :string)
  (-invoke [_ {:keys [name]} _ctx]
    (if-not (proto/runtime-tool-store? session)
      (tr/encode-result {:ok false :tool "tool_forget" :error "No factory session on context"})
      (tr/encode-result
       {:ok true
        :tool "tool_forget"
        :pending "same-exchange"
        :tool-name name
        :before (proto/-status session)
        :transition {:op :forget-runtime-tool :tool-name name}}))))

(defrecord ToolListRuntimeTool [session]
  tool/Tool
  (-name [_] "tool_list_runtime")
  (-description [_]
    "List tools created with tool_define in this session, plus promoted overlay names. Read-only.")
  (-input-schema [_] ListInput)
  (-output-schema [_] :string)
  (-invoke [_ _args ctx]
    (if-not (proto/runtime-tool-store? session)
      (tr/encode-result {:ok false :tool "tool_list_runtime" :error "No factory session on context"})
      (tr/encode-result
       {:ok true
        :tool "tool_list_runtime"
        :status (proto/-status session)
        :specs (proto/-specs session)
        :state-specs (or (get-in ctx [:agent/state :agent/runtime-tools]) {})}))))

(defrecord ToolPromoteTool [session]
  tool/Tool
  (-name [_] "tool_promote")
  (-description [_]
    "Promote a tool_define spec to reusable on-disk source. target=workspace (default) writes .lateralus/promoted/<name>/ and load-files it — works in Docker/uberjar when the workspace is writable. target=project writes src/kschltz/agent/tools/promoted/ plus a test ns (host source tree). as-plugin true also writes a real interceptor plugin. Explicit only — defining a tool does not write files.")
  (-input-schema [_] PromoteInput)
  (-output-schema [_] :string)
  (-invoke [_ {:keys [name as-plugin target]} _ctx]
    (if-not (proto/runtime-tool-store? session)
      (tr/encode-result {:ok false :tool "tool_promote" :error "No factory session on context"})
      (if-not (proto/-dynamic-enabled? session)
        (tr/encode-result {:ok false :tool "tool_promote"
                           :error "Dynamic tool factory is disabled"
                           :phase "disabled"})
        (tr/encode-result
         {:ok true
          :tool "tool_promote"
          :pending "same-exchange"
          :tool-name name
          :before (proto/-status session)
          :transition (cond-> {:op :promote-runtime-tool :tool-name name}
                        (some? as-plugin) (assoc :as-plugin (boolean as-plugin))
                        target (assoc :target (coerce-target target)))})))))

(defn factory-tools-registry
  "Control tools bound to `session`. Empty when session is missing."
  [session]
  (if-not (proto/runtime-tool-store? session)
    {}
    {"tool_define"        (->ToolDefineTool session)
     "tool_forget"        (->ToolForgetTool session)
     "tool_list_runtime"  (->ToolListRuntimeTool session)
     "tool_promote"       (->ToolPromoteTool session)}))

(m/=> normalize-tool-spec [:=> [:cat [:maybe :map]] :map])
(m/=> factory-tools-registry
      [:=> [:cat [:maybe :any]] :map])

(defn instrument!
  []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.tools.factory.tools)]}))

(instrument!)
