(ns kschltz.agent.evolution.evaluator-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.evolution.evaluator :as evaluator]
            [kschltz.agent.evolution.protocol :as proto]
            [kschltz.agent.evolution.state :as state]))

(defrecord FakeRunner [result]
  proto/CommandRunner
  (-run-command! [_ _] result))

(deftest strict-policy-rejects-unisolated-offline-gate
  (let [command {:status :ok :exit-code 0 :stdout "" :stderr ""
                 :duration-ms 1 :truncated? false
                 :network-isolated? false :workspace-isolated? false}
        gates [{:id "tests" :kind :regression :argv ["test"]
                :timeout-ms 100 :network :deny :required? true}]
        result (evaluator/evaluate!
                (->FakeRunner command) "/tmp" gates
                (state/normalize-policy nil))]
    (is (false? (:passed? (first result))))
    (is (= :rejected (get-in result [0 :command :status])))))
