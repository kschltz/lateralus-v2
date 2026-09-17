(ns kschltz.agent.evolution.state-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.evolution.state :as state]))

(deftest path-policy-rejects-empty-protected-and-outside-changes
  (let [policy (state/normalize-policy nil)]
    (is (= :no-changes
           (:error (first (state/path-violations [] policy)))))
    (is (some #(= :protected-path (:error %))
              (state/path-violations
               ["src/kschltz/agent/evolution/state.clj"] policy)))
    (is (some #(= :outside-allowed-paths (:error %))
              (state/path-violations ["scripts/deploy.sh"] policy)))))

(deftest baseline-relative-gate-decision
  (let [command {:status :failed :exit-code 1 :stdout "" :stderr ""
                 :duration-ms 1 :truncated? false
                 :network-isolated? true :workspace-isolated? true}
        failed {:id "suite" :kind :regression :required? true
                :passed? false :command command}
        fixed (assoc failed :passed? true
                     :command (assoc command :status :ok :exit-code 0))
        acceptance (assoc fixed :id "acceptance" :kind :acceptance)]
    (testing "an unchanged known regression is not a new regression"
      (is (:accepted? (state/gate-decision [failed] [failed]))))
    (testing "an acceptance gate must actually pass"
      (is (not (:accepted?
                (state/gate-decision [acceptance]
                                     [(assoc acceptance :passed? false)])))))
    (testing "fixing a baseline failure passes"
      (is (:accepted? (state/gate-decision [failed] [fixed]))))
    (testing "matching isolation failures and timeouts never pass as unchanged"
      (doseq [status [:rejected :timeout]]
        (is (not
             (:accepted?
              (state/gate-decision
               [failed]
               [(assoc failed :command
                       (assoc command :status status
                              :network-isolated? false
                              :workspace-isolated? false))]))))))))

(deftest token-budget-is-a-hard-supervisor-gate
  (let [policy (assoc (state/normalize-policy nil) :max-token-usage 10)
        implementation {:status :completed :turns 1 :tool-calls 1
                        :token-usage 11 :summary "done"}]
    (is (some #(= :token-budget (:error %))
              (state/budget-violations 0 1 implementation policy)))))
