(ns kschltz.agent.tools.workflow.protocol
  "In-process artifact-driven workflow engine.

   The scheduler never takes an order. It computes a ready set — actions
   whose :needs are present and whose :produces are not already
   satisfied — runs that set as one wave, and repeats."
  (:require [malli.core :as m]))

(def ArtifactId
  [:or [:string {:min 1}] :keyword :symbol])

(defn- run-op
  [spec]
  (some-> spec :op keyword))

(def RunSpec
  "How an action produces artifacts. `:eval` compiles a fn of the
   current store; `:tool` invokes a registry Tool; `:literal` writes
   a map (tests / seeds)."
  [:multi {:dispatch run-op}
   [:eval [:map {:closed true}
           [:op [:enum :eval "eval"]]
           [:code [:string {:min 1}]]]]
   [:tool [:map {:closed true}
           [:op [:enum :tool "tool"]]
           [:name [:string {:min 1}]]
           [:args {:optional true} :map]]]
   [:literal [:map {:closed true}
              [:op [:enum :literal "literal"]]
              [:values :map]]]])

(def Action
  [:map {:closed true}
   [:name [:string {:min 1}]]
   [:needs [:vector ArtifactId]]
   [:produces [:vector {:min 1} ArtifactId]]
   [:run RunSpec]])

(defprotocol WorkflowEngine
  "Session-owned action registry + artifact store."
  (-register-action! [eng action]
    "Upsert an action. Returns status. Raises `ex-info` `{:phase}` on
     invalid action.")
  (-seed! [eng artifacts]
    "Merge artifact id→value into the store. Returns status.")
  (-run! [eng opts]
    "Schedule and execute from data dependencies. `opts` may include
     `:ctx` for `:tool` runs. Returns a result map. Must not raise for
     blocked/cycle/missing — those are `:status :blocked`.")
  (-status [eng]
    "Serializable inventory. MUST NOT raise.")
  (-clear! [eng what]
    "Reset `:actions`, `:store`, or `:all`. Returns status."))

(defn workflow-engine?
  [x]
  (satisfies? WorkflowEngine x))

(def RunResult
  [:map
   [:status [:enum :done :blocked :error]]
   [:ran [:vector :string]]
   [:parallel-waves [:vector [:vector :string]]]
   [:store :map]
   [:blocked? :boolean]
   [:errors [:vector :map]]
   [:blocked {:optional true} [:vector :string]]
   [:cycle {:optional true} [:maybe [:vector [:vector :string]]]]
   [:missing {:optional true} [:vector :map]]])

(defn valid-action?
  [action]
  (m/validate Action action))

(defn valid-run-result?
  [result]
  (m/validate RunResult result))
