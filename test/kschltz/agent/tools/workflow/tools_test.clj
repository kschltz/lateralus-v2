(ns kschltz.agent.tools.workflow.tools-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.workflow.session :as session]
            [kschltz.agent.tools.workflow.tools :as tools]))

(defn- parse
  [s]
  (json/parse-string s true))

(defn- invoke
  [reg name args]
  (parse (tool/invoke-tool (get reg name) args {})))

(defn- register
  [reg name needs produces values]
  (invoke reg "workflow_register_action"
          {:name name :needs needs :produces produces
           :run {:op :literal :values values}}))

(deftest registry-names
  (let [reg (tools/workflow-tools-registry (session/workflow-session))]
    (is (= #{"workflow_register_action" "workflow_seed"
             "workflow_run" "workflow_status" "workflow_clear"}
           (set (keys reg))))
    (doseq [t (vals reg)]
      (is (tool/tool? t)))))

(deftest tools-prove-diamond-cycle-and-missing
  (let [reg (tools/workflow-tools-registry (session/workflow-session))]
    (register reg "A" [] ["x"] {"x" 1})
    (register reg "B" ["x"] ["y"] {"y" 2})
    (register reg "C" ["x"] ["z"] {"z" 3})
    (register reg "D" ["y" "z"] ["w"] {"w" 4})
    (let [result (invoke reg "workflow_run" {})
          waves (:parallel-waves result)]
      (is (true? (:ok result)))
      (is (= "done" (:status result)))
      (is (false? (:blocked? result)))
      (is (= 3 (count waves)))
      (is (= ["A"] (first waves)))
      (is (= #{"B" "C"} (set (second waves))))
      (is (= ["D"] (last waves)))
      (is (= {:x 1 :y 2 :z 3 :w 4} (:store result))))
    (invoke reg "workflow_clear" {:what "all"})
    (register reg "f" ["b"] ["a"] {"a" 1})
    (register reg "g" ["a"] ["b"] {"b" 1})
    (let [blocked (invoke reg "workflow_run" {})]
      (is (= "blocked" (:status blocked)))
      (is (true? (:blocked? blocked)))
      (is (empty? (:ran blocked)))
      (is (seq (:cycle blocked))))
    (invoke reg "workflow_clear" {:what "all"})
    (register reg "h" ["seed"] ["out"] {"out" 1})
    (let [missing (invoke reg "workflow_run" {})]
      (is (= "blocked" (:status missing)))
      (is (true? (:blocked? missing)))
      (is (= [{:action "h" :missing ["seed"]}] (:missing missing))))
    (invoke reg "workflow_seed" {:artifacts {:seed true}})
    (let [unblocked (invoke reg "workflow_run" {})]
      (is (= "done" (:status unblocked)))
      (is (= 1 (get-in unblocked [:store :out]))))))

(deftest status-and-clear-are-callable
  (let [reg (tools/workflow-tools-registry (session/workflow-session))]
    (register reg "A" [] ["x"] {"x" 1})
    (let [st (invoke reg "workflow_status" {})]
      (is (true? (:ok st)))
      (is (= 1 (:action-count st))))
    (is (true? (:ok (invoke reg "workflow_clear" {}))))
    (is (= 0 (:action-count (invoke reg "workflow_status" {}))))))
