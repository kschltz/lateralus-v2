(ns kschltz.agent.tools.workflow.session
  "Atom-backed WorkflowEngine. Protocol methods delegate to
   Malli-instrumented impl fns."
  (:require [kschltz.agent.tools.workflow.engine :as engine]
            [kschltz.agent.tools.workflow.protocol :as proto]
            [malli.core :as m]
            [malli.error :as me]
            [malli.instrument :as mi]))

(defn- raise
  [phase msg data]
  (throw (ex-info msg (merge {:phase phase} data))))

(def ^:private run-example
  "Canonical :run shapes shown in error messages so the model can
   self-correct instead of retrying blind (see session 675706dd)."
  (str "\"run\" must be one of:\n"
       "  {:op :eval    :code \"(fn [store] {\\\"y\\\" ...})\"}\n"
       "  {:op :tool    :name \"tool_name\" :args {...}}\n"
       "  {:op :literal :values {...}}\n"
       "A bare string for :run is treated as :eval code."))

(defn- normalize-action
  [action]
  (let [action (cond-> action
                 ;; Accept a bare :run string as :eval code.
                 (string? (:run action))
                 (assoc :run {:op :eval :code (:run action)})

                 (string? (get-in action [:run :op]))
                 (update-in [:run :op] keyword)

                 ;; Accept :code/:name/:values shorthand without :op when
                 ;; unambiguous.
                 (and (map? (:run action))
                      (not (:op (:run action)))
                      (:code (:run action)))
                 (assoc-in [:run :op] :eval)

                 (and (map? (:run action))
                      (not (:op (:run action)))
                      (:name (:run action)))
                 (assoc-in [:run :op] :tool)

                 (and (map? (:run action))
                      (not (:op (:run action)))
                      (:values (:run action)))
                 (assoc-in [:run :op] :literal)

                 (sequential? (:needs action))
                 (update :needs #(mapv str %))

                 (sequential? (:produces action))
                 (update :produces #(mapv str %)))]
    (when-not (proto/valid-action? action)
      (raise :compile
             (str "invalid action: "
                  (pr-str (some-> (m/explain proto/Action action) me/humanize))
                  "\n" run-example
                  "\nreceived :run: " (pr-str (:run action)))
             {:action action}))
    action))

(defn register-action-impl
  [state action]
  (let [action (normalize-action action)]
    (swap! state assoc-in [:actions (:name action)] action)
    {:ok true :action (:name action)}))

(defn seed-impl
  [state artifacts]
  (when-not (map? artifacts)
    (raise :seed "artifacts must be a map" {}))
  (let [normalized (into {} (map (fn [[k v]] [(name k) v]) artifacts))]
    (swap! state update :store merge normalized)
    {:ok true :seeded (vec (sort (keys normalized)))}))

(defn run-impl
  [state opts]
  (let [{:keys [actions store]} @state
        result (engine/schedule actions (or store {}) (:ctx opts))]
    (swap! state assoc :store (:store result))
    result))

(defn status-impl
  [state]
  (let [{:keys [actions store]} @state]
    {:actions (mapv #(select-keys % [:name :needs :produces :run])
                    (vals actions))
     :store (or store {})
     :action-count (count actions)
     :artifact-count (count store)}))

(defn clear-impl
  [state what]
  (let [what (keyword (or what :all))]
    (swap! state
           (fn [st]
             (case what
               :actions (assoc st :actions {})
               :store (assoc st :store {})
               (assoc st :actions {} :store {}))))
    {:ok true :cleared what}))

;; Not instrumented with proto/Action: normalize-action must see raw
;; (possibly malformed) model input to repair/annotate it; it raises a
;; humanized error for anything still invalid.
(m/=> register-action-impl [:=> [:cat :any :any] :map])
(m/=> seed-impl [:=> [:cat :any :map] :map])
(m/=> run-impl [:=> [:cat :any [:maybe :map]] proto/RunResult])
(m/=> status-impl [:=> [:cat :any] :map])
(m/=> clear-impl [:=> [:cat :any [:maybe [:or :keyword :string]]] :map])

(deftype WorkflowSession [state]
  proto/WorkflowEngine
  (-register-action! [_ action]
    (register-action-impl state action))

  (-seed! [_ artifacts]
    (seed-impl state artifacts))

  (-run! [_ opts]
    (run-impl state opts))

  (-status [_]
    (status-impl state))

  (-clear! [_ what]
    (clear-impl state what)))

(defn workflow-session
  ([] (workflow-session {}))
  ([_opts]
   (->WorkflowSession (atom {:actions {} :store {}}))))

(m/=> workflow-session
      [:function
       [:=> :cat [:fn proto/workflow-engine?]]
       [:=> [:cat [:maybe :map]] [:fn proto/workflow-engine?]]])

(defn instrument!
  []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.tools.workflow.session)]}))

(instrument!)
