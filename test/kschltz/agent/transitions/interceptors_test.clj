(ns kschltz.agent.transitions.interceptors-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.transitions :as transitions]
            [kschltz.agent.transitions.interceptors :as interceptors]))

(deftest harvest-and-apply-preserve-the-transition-boundary
  (let [encoded (transitions/encode-result
                 {:ok true
                  :transition {:op :set-system-message
                               :message "new"}})
        harvested (interceptors/harvest-transitions
                   [{:call {:id "1"} :result encoded}])
        out (interceptors/apply-queued-transitions
             {:agent/state {:agent/system-message "old"}
              :agent/transitions (:transitions harvested)
              :tool/results (:results harvested)
              :agent/state-delta {}})]
    (is (= [{:op :set-system-message :message "new"}]
           (:transitions harvested)))
    (is (= "new" (get-in out [:agent/state :agent/system-message])))
    (is (= "new"
           (get-in out [:agent/state-delta :agent/system-message])))
    (is (empty? (:agent/transitions out)))))
