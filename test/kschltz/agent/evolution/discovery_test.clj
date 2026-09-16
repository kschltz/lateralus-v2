(ns kschltz.agent.evolution.discovery-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.evolution.discovery :as discovery]))

(def failed-command
  {:status :failed :exit-code 1 :stdout "" :stderr "one failure"
   :duration-ms 2 :truncated? false :network-isolated? true
   :workspace-isolated? true})

(deftest failing-acceptance-gate-becomes-an-evidence-backed-proposal
  (let [gates [{:id "parser" :kind :acceptance :argv ["test"]
                :timeout-ms 100 :network :deny :required? true
                :candidate-paths ["src/parser.clj"]}]
        results [{:id "parser" :kind :acceptance :required? true
                  :passed? false :command failed-command}]
        proposal (discovery/proposal-from-failures gates results)]
    (is (= :self-diagnosis (:source proposal)))
    (is (= ["src/parser.clj"] (:paths proposal)))
    (is (re-find #"one failure" (first (:evidence proposal))))))

(deftest clean-gates-produce-no-work
  (is (nil? (discovery/proposal-from-failures
             []
             []))))
