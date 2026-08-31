(ns kschltz.agent.tools.factory.tools
  "Control tools for runtime tool define / forget / list / promote.

   Mutating tools propose closed transitions. Compile, registry overlay,
   and disk promotion run in the transitions apply interceptor."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
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

(declare json-schema->malli)

(defn- object-schema->malli
  [schema]
  (let [properties-map (or (:properties schema) (:keys schema) {})
        required (into
                  (set (map #(keyword (name %)) (:required schema)))
                  (keep (fn [[k property]]
                          (when (true? (:required property))
                            (keyword (name k)))))
                  properties-map)
        properties (sort-by (comp name key) properties-map)]
    (into
     [:map]
     (map (fn [[raw-key property-schema]]
            (let [k (keyword (name raw-key))
                  value-schema (json-schema->malli property-schema)]
              (if (contains? required k)
                [k value-schema]
                [k {:optional true} value-schema]))))
     properties)))

(defn- json-schema->malli
  [schema]
  (let [schema (if (map? schema) schema {})
        schema-type (some-> (:type schema) name keyword)]
    (case schema-type
      :map (object-schema->malli schema)
      :object (object-schema->malli schema)
      :string :string
      :int :int
      :integer :int
      :double :double
      :float :double
      :number :double
      :bool :boolean
      :boolean :boolean
      :array [:vector (json-schema->malli (:items schema))]
      :vector [:vector (json-schema->malli (:items schema))]
      :any)))

(defn- normalize-input-schema
  [schema]
  (cond
    (map? schema)
    (json-schema->malli
     (if (contains? schema :type)
       schema
       {:type :map
        :keys (into {}
                    (map (fn [[k v]]
                           [k (assoc (if (map? v) v {:type v})
                                     :required true)]))
                    schema)}))

    (string? schema)
    (let [parsed (try
                   (edn/read-string schema)
                   (catch Throwable _ ::invalid))]
      (if (map? parsed)
        (normalize-input-schema parsed)
        schema))

    :else schema))

(defn normalize-tool-spec
  "Accept the shapes models actually emit; persist a closed ToolSpec."
  [spec]
  (let [model-spec (into {}
                         (map (fn [[k v]] [(kebab-key k) v]))
                         (or spec {}))
        schema-alias (cond
                       (contains? model-spec :schema) (:schema model-spec)
                       (contains? model-spec :malli) (:malli model-spec)
                       :else nil)
        aliased (cond-> model-spec
                  (and (nil? (:input-schema model-spec))
                       (some? schema-alias))
                  (assoc :input-schema schema-alias))
        spec (cond-> (dissoc aliased :schema :malli)
               (some? (:input-schema aliased))
               (update :input-schema normalize-input-schema))]
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

(def TestInput
  [:and
   [:map
    [:name proto/portable-tool-name]
    [:arguments {:optional true} :map]
    [:args {:optional true} :map]
    [:expected-output :string]
    [:input-context {:optional true} :map]
    [:output-context {:optional true} :map]]
   [:fn {:error/message "tool_test requires arguments or args"}
    (fn [input]
      (or (map? (:arguments input))
          (map? (:args input))))]])

(def ListInput
  [:map {:closed true}])

(def PromoteInput
  [:and
   [:map {:closed true}
    [:name {:optional true} proto/portable-tool-name]
    [:tool {:optional true} proto/portable-tool-name]
    [:as-plugin {:optional true} :boolean]
    [:target {:optional true} [:enum "workspace" "project" :workspace :project]]]
   [:fn {:error/message "tool_promote requires name or tool"}
    (fn [input]
      (or (string? (:name input))
          (string? (:tool input))))]])

(defn- coerce-target
  [target]
  (when target
    (keyword (name target))))

(defrecord ToolDefineTool [session]
  tool/Tool
  (-name [_] "tool_define")
  (-description [_]
    "Create a callable session tool now: name, description, input-schema (EDN Malli string or schema object), invoke (Clojure string of (fn [args ctx] result); one-argument (fn [args] result) is also accepted). Do not use clojure_eval. Network I/O must use existing protocol-backed web_* or MCP tools, never direct sockets/URL/slurp. Call tool_test after define; only a passing exact-output test permits tool_promote. Optional libs/require/alias and interceptor-slot plus interceptor-enter/leave/error. Emits a transition; compile + registry refresh happen before compose.")
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

(defn- invocation-error?
  [tool-name actual]
  (or (and (str/starts-with? actual (str "Tool '" tool-name "'"))
           (str/includes? actual " validation failed:"))
      (= "execution"
         (try
           (:phase (json/parse-string actual true))
           (catch Throwable _ nil)))))

(defrecord ToolTestTool [session]
  tool/Tool
  (-name [_] "tool_test")
  (-description [_]
    "Test one tool_define tool before promotion. Calls it with arguments through the current guarded registry and passes only when its string result exactly equals expected-output. A passing test is tied to the current tool spec; redefining the tool invalidates it. Inspect actual on failure, fix or adjust the tool, and test again before tool_promote.")
  (-input-schema [_] TestInput)
  (-output-schema [_] :string)
  (-invoke [_ {:keys [name arguments args expected-output]} ctx]
    (if-not (proto/runtime-tool-store? session)
      (tr/encode-result {:ok false :tool "tool_test"
                         :error "No factory session on context"})
      (let [spec (get (proto/-specs session) name)
            runtime-tool (get (proto/-registry session) name)
            effective-tool (or (tool/resolve-tool
                                (:agent/tool-registry ctx) name)
                               runtime-tool)]
        ;; #region agent log
        (spit "/opt/cursor/logs/debug.log"
              (str (json/generate-string
                    {:hypothesisId "E"
                     :location "factory/tools.clj:tool-test:before"
                     :message "resolved runtime tool for lifecycle test"
                     :data {:sessionId (:exchange/session-id ctx)
                            :turnId (:stream/turn-id ctx)
                            :toolName name
                            :factoryStatus (proto/-status session)
                            :specPresent (boolean spec)
                            :runtimeToolPresent (boolean runtime-tool)
                            :contextToolPresent
                            (boolean (tool/resolve-tool
                                      (:agent/tool-registry ctx) name))
                            :effectiveToolPresent (boolean effective-tool)}
                     :timestamp (System/currentTimeMillis)})
                   "\n")
              :append true)
        ;; #endregion
        (cond
          (nil? spec)
          (tr/encode-result {:ok false :tool "tool_test"
                             :phase "unknown"
                             :error (str "Unknown ephemeral runtime tool: " name)})

          (nil? effective-tool)
          (tr/encode-result {:ok false :tool "tool_test"
                             :phase "unavailable"
                             :error (str "Runtime tool is not callable: " name)})

          :else
          (let [actual (tool/invoke-tool effective-tool (or arguments args) ctx)
                passed? (and (not (invocation-error? name actual))
                             (= expected-output actual))]
            (tr/encode-result
             (cond-> {:ok passed?
                      :tool "tool_test"
                      :tool-name name
                      :expected expected-output
                      :actual actual}
               (not passed?)
               (assoc :phase (if (invocation-error? name actual)
                               "execution"
                               "assertion")
                      :error (if (invocation-error? name actual)
                               "Tool invocation failed"
                               "Actual output did not exactly match expected-output"))
               passed?
               (assoc :pending "same-exchange"
                      :transition
                      {:op :record-runtime-tool-test
                       :tool-name name
                       :spec-id (proto/spec-id spec)})))))))))

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
  (-invoke [_ {:keys [name tool as-plugin target]} _ctx]
    (let [name (or name tool)]
      (if-not (proto/runtime-tool-store? session)
        (tr/encode-result {:ok false :tool "tool_promote" :error "No factory session on context"})
        (if-not (proto/-dynamic-enabled? session)
          (tr/encode-result {:ok false :tool "tool_promote"
                             :error "Dynamic tool factory is disabled"
                             :phase "disabled"})
          (let [status (proto/-status session)
              ephemeral (set (:ephemeral status))
              tested (set (:tested status))
              promoted (set (:promoted status))]
            (cond
              (contains? promoted name)
              (tr/encode-result {:ok true
                                 :tool "tool_promote"
                                 :tool-name name
                                 :phase "already-promoted"
                                 :status status})

              (not (contains? ephemeral name))
              (tr/encode-result {:ok false
                                 :tool "tool_promote"
                                 :tool-name name
                                 :phase "unknown"
                                 :error (str "Unknown ephemeral runtime tool: " name
                                             ". Call tool_define first.")
                                 :status status})

              (not (contains? tested name))
              (tr/encode-result {:ok false
                                 :tool "tool_promote"
                                 :tool-name name
                                 :phase "needs-test"
                                 :error (str "Runtime tool must pass tool_test before promotion: "
                                             name)
                                 :status status})

              :else
              (tr/encode-result
               {:ok true
                :tool "tool_promote"
                :pending "same-exchange"
                :tool-name name
                :before status
                :transition (cond-> {:op :promote-runtime-tool :tool-name name}
                              (some? as-plugin) (assoc :as-plugin (boolean as-plugin))
                              target (assoc :target (coerce-target target)))}))))))))

(defn factory-tools-registry
  "Control tools bound to `session`. Empty when session is missing."
  [session]
  (if-not (proto/runtime-tool-store? session)
    {}
    {"tool_define"        (->ToolDefineTool session)
     "tool_test"          (->ToolTestTool session)
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
