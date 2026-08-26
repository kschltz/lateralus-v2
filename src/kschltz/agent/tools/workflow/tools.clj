(ns kschltz.agent.tools.workflow.tools
  "First-class workflow_* tools bound to a WorkflowEngine."
  (:require [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.workflow.protocol :as proto]
            [kschltz.agent.transitions :as tr]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def RegisterInput proto/Action)

(def SeedInput
  [:map {:closed true}
   [:artifacts :map]])

(def RunInput
  [:map {:closed true}])

(def StatusInput
  [:map {:closed true}])

(def ClearInput
  [:map {:closed true}
   [:what {:optional true} [:enum "actions" "store" "all" :actions :store :all]]])

(defn- missing-engine
  [tool-name]
  (tr/encode-result {:ok false :tool tool-name :error "No workflow engine on context"}))

(defrecord WorkflowRegisterActionTool [engine]
  tool/Tool
  (-name [_] "workflow_register_action")
  (-description [_]
    "Upsert a workflow action. needs/produces are artifact ids. run is {:op :eval :code \"(fn [store] {\\\"y\\\" …})\"}, {:op :tool :name … :args …}, or {:op :literal :values {…}}. The engine never takes an order — it schedules from data dependencies.")
  (-input-schema [_] RegisterInput)
  (-output-schema [_] :string)
  (-invoke [_ action _ctx]
    (if-not (proto/workflow-engine? engine)
      (missing-engine "workflow_register_action")
      (try
        (tr/encode-result (merge {:tool "workflow_register_action"}
                                 (proto/-register-action! engine action)))
        (catch Throwable t
          (tr/encode-result {:ok false
                             :tool "workflow_register_action"
                             :error (ex-message t)
                             :phase (name (or (:phase (ex-data t)) :tool))}))))))

(defrecord WorkflowSeedTool [engine]
  tool/Tool
  (-name [_] "workflow_seed")
  (-description [_]
    "Put one or more artifacts into the workflow store. Pass :artifacts as a map of id → value. Missing artifacts are unsatisfied needs.")
  (-input-schema [_] SeedInput)
  (-output-schema [_] :string)
  (-invoke [_ {:keys [artifacts]} _ctx]
    (if-not (proto/workflow-engine? engine)
      (missing-engine "workflow_seed")
      (tr/encode-result (merge {:tool "workflow_seed"}
                               (proto/-seed! engine artifacts))))))

(defrecord WorkflowRunTool [engine]
  tool/Tool
  (-name [_] "workflow_run")
  (-description [_]
    "Schedule and execute from artifact dependencies. Returns status, ran, parallel-waves, store, blocked?, errors. Ready actions in one wave run together; the engine does not invent a sequence.")
  (-input-schema [_] RunInput)
  (-output-schema [_] :string)
  (-invoke [_ _args ctx]
    (if-not (proto/workflow-engine? engine)
      (missing-engine "workflow_run")
      (tr/encode-result (merge {:ok true :tool "workflow_run"}
                               (proto/-run! engine {:ctx ctx}))))))

(defrecord WorkflowStatusTool [engine]
  tool/Tool
  (-name [_] "workflow_status")
  (-description [_]
    "Inspect registered actions and the current artifact store.")
  (-input-schema [_] StatusInput)
  (-output-schema [_] :string)
  (-invoke [_ _args _ctx]
    (if-not (proto/workflow-engine? engine)
      (missing-engine "workflow_status")
      (tr/encode-result (merge {:ok true :tool "workflow_status"}
                               (proto/-status engine))))))

(defrecord WorkflowClearTool [engine]
  tool/Tool
  (-name [_] "workflow_clear")
  (-description [_]
    "Reset workflow actions, store, or both (what=all, default).")
  (-input-schema [_] ClearInput)
  (-output-schema [_] :string)
  (-invoke [_ {:keys [what]} _ctx]
    (if-not (proto/workflow-engine? engine)
      (missing-engine "workflow_clear")
      (tr/encode-result (merge {:tool "workflow_clear"}
                               (proto/-clear! engine (or what :all)))))))

(defn workflow-tools-registry
  [engine]
  (if-not (proto/workflow-engine? engine)
    {}
    {"workflow_register_action" (->WorkflowRegisterActionTool engine)
     "workflow_seed"            (->WorkflowSeedTool engine)
     "workflow_run"             (->WorkflowRunTool engine)
     "workflow_status"          (->WorkflowStatusTool engine)
     "workflow_clear"           (->WorkflowClearTool engine)}))

(m/=> workflow-tools-registry [:=> [:cat [:maybe :any]] :map])

(defn instrument!
  []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.tools.workflow.tools)]}))

(instrument!)
