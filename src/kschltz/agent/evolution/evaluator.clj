(ns kschltz.agent.evolution.evaluator
  "Independent gate evaluation over the constrained command boundary."
  (:require [kschltz.agent.evolution.protocol :as proto]
            [kschltz.agent.evolution.schemas :as schemas]
            [malli.core :as m]
            [malli.instrument :as mi]))

(defn evaluate!
  [runner workspace gate-specs policy]
  (mapv
   (fn [{:keys [id kind argv timeout-ms network required?]}]
     (let [result (proto/-run-command!
                   runner
                   {:argv argv
                    :cwd workspace
                    :timeout-ms timeout-ms
                    :max-output-bytes (:max-output-bytes policy)
                    :isolation :workspace
                    :network network})
           isolation-required? (and (:require-network-isolation? policy)
                                    (= :deny network))
           network-isolated? (or (not isolation-required?)
                                 (:network-isolated? result))
           workspace-isolated? (or (not (:require-workspace-isolation? policy))
                                   (:workspace-isolated? result))
           isolated? (and network-isolated? workspace-isolated?)
           result (if isolated?
                    result
                    (assoc result
                           :status :rejected
                           :error "required process isolation was unavailable"))]
       {:id id
        :kind kind
        :required? required?
        :passed? (and (= :ok (:status result)) isolated?)
        :command result}))
   gate-specs))

(defrecord CommandEvaluator [runner]
  proto/Evaluator
  (-evaluate! [_ workspace gate-specs policy]
    (evaluate! runner workspace gate-specs policy)))

(defn command-evaluator
  [runner]
  (->CommandEvaluator runner))

(m/=> evaluate!
      [:=> [:cat
            [:fn proto/command-runner?]
            :string
            [:vector schemas/GateSpec]
            schemas/Policy]
       [:vector schemas/GateResult]])
(m/=> command-evaluator
      [:=> [:cat [:fn proto/command-runner?]] [:fn proto/evaluator?]])

(defn instrument! []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.evolution.evaluator)]}))

(instrument!)
