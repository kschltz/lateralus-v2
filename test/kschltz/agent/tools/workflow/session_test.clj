(ns kschltz.agent.tools.workflow.session-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.tools.workflow.protocol :as proto]
            [kschltz.agent.tools.workflow.session :as session]))

(deftest session-satisfies-protocol
  (is (proto/workflow-engine? (session/workflow-session))))

(deftest register-seed-run-clear
  (let [eng (session/workflow-session)]
    (is (= {:ok true :action "A"}
           (proto/-register-action!
            eng {:name "A" :needs [] :produces ["x"]
                 :run {:op :literal :values {"x" 1}}})))
    (is (= {:ok true :seeded ["pre"]}
           (proto/-seed! eng {:pre true})))
    (let [result (proto/-run! eng {})]
      (is (= :done (:status result)))
      (is (= 1 (get-in result [:store "x"])))
      (is (true? (get-in result [:store "pre"]))))
    (is (= 2 (:artifact-count (proto/-status eng))))
    (is (= {:ok true :cleared :store} (proto/-clear! eng :store)))
    (is (= 0 (:artifact-count (proto/-status eng))))
    (is (= 1 (:action-count (proto/-status eng))))
    (is (= {:ok true :cleared :all} (proto/-clear! eng :all)))
    (is (= 0 (:action-count (proto/-status eng))))))

(deftest upsert-replaces-action
  (let [eng (session/workflow-session)]
    (proto/-register-action!
     eng {:name "A" :needs [] :produces ["x"]
          :run {:op :literal :values {"x" 1}}})
    (proto/-register-action!
     eng {:name "A" :needs [] :produces ["x"]
          :run {:op :literal :values {"x" 9}}})
    (is (= 9 (get-in (proto/-run! eng {}) [:store "x"])))))
