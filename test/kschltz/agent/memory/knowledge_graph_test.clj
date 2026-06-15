(ns kschltz.agent.memory.knowledge-graph-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.memory.knowledge-graph :as kg]))

(deftest update-graph-attaches-message-to-entities
  (is (= {"dark" #{"m1"} "mode" #{"m1"}}
         (kg/update-graph {} "m1" #{"dark" "mode"})))
  (is (= {"dark" #{"m1" "m2"}}
         (kg/update-graph {"dark" #{"m1"}} "m2" #{"dark"}))))

(deftest build-graph-from-messages
  (let [graph (kg/build-graph [{:msg-id "m1" :content "dark mode"}
                               {:msg-id "m2" :content "light mode"}])]
    (is (= #{"m1"} (get graph "dark")))
    (is (= #{"m2"} (get graph "light")))
    (is (= #{"m1" "m2"} (get graph "mode")))))

(deftest graph-score-favors-direct-overlap
  (let [graph {"dark" #{"m1"} "mode" #{"m1"}}
        scores (kg/graph-score graph #{"dark" "mode"})]
    (is (= 2.0 (get scores "m1")))
    (is (nil? (get scores "m2")))))
