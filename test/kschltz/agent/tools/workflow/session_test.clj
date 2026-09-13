(ns kschltz.agent.tools.workflow.session-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
      (is (= [{:action "A" :status :passed :produces ["x"] :ran true}]
             (:checkpoints result)))
      (is (= 1 (get-in result [:store "x"])))
      (is (true? (get-in result [:store "pre"]))))
    (is (= 2 (:artifact-count (proto/-status eng))))
    (is (= {:ok true :cleared :store} (proto/-clear! eng :store)))
    (is (= 0 (:artifact-count (proto/-status eng))))
    (is (= 1 (:action-count (proto/-status eng))))
    (is (= {:ok true :cleared :all} (proto/-clear! eng :all)))
    (is (= 0 (:action-count (proto/-status eng))))))

(deftest checkpoint-failures-and-snapshots-survive-a-new-session
  (let [eng (session/workflow-session)]
    (proto/-register-action!
     eng {:name "explode" :needs [] :produces ["done"]
          :run {:op :eval :code "(fn [_] (throw (ex-info \"boom\" {})))"}})
    (let [result (proto/-run! eng {})
          checkpoint (first (:checkpoints result))
          snapshot (proto/-snapshot eng)
          fresh (session/workflow-session)]
      (is (= :error (:status result)))
      (is (= :failed (:status checkpoint)))
      (is (= "boom" (:error checkpoint)))
      (is (= {:ok true :action-count 1 :artifact-count 0}
             (proto/-load-snapshot! fresh snapshot)))
      (is (= (:checkpoints (proto/-status eng))
             (:checkpoints (proto/-status fresh))))
      (is (= :error (:last-status (proto/-status fresh)))))))

(deftest upsert-replaces-action
  (let [eng (session/workflow-session)]
    (proto/-register-action!
     eng {:name "A" :needs [] :produces ["x"]
          :run {:op :literal :values {"x" 1}}})
    (proto/-register-action!
     eng {:name "A" :needs [] :produces ["x"]
          :run {:op :literal :values {"x" 9}}})
    (is (= 9 (get-in (proto/-run! eng {}) [:store "x"])))))

(deftest register-action-repairs-leniency-shapes
  (testing "bare :run string becomes :eval code"
    (let [st (atom {:actions {} :store {}})
          res (session/register-action-impl
               st {:name "a" :needs [] :produces ["y"]
                   :run "(fn [store] {\"y\" 1})"})]
      (is (:ok res))
      (is (= {:op :eval :code "(fn [store] {\"y\" 1})"}
             (get-in @st [:actions "a" :run])))))
  (testing ":run map without :op infers from content"
    (let [st (atom {:actions {} :store {}})]
      (is (:ok (session/register-action-impl
                st {:name "b" :needs [] :produces ["z"]
                    :run {:code "(fn [s] {})"}})))
      (is (= :eval (get-in @st [:actions "b" :run :op]))))
    (let [st (atom {:actions {} :store {}})]
      (is (:ok (session/register-action-impl
                st {:name "d" :needs [] :produces ["z"]
                    :run {:op "tool" :name "file_read"}})))
      (is (= :tool (get-in @st [:actions "d" :run :op])))))
  (testing "unrepairable :run raises an error that shows the example"
    (try
      (session/register-action-impl
       (atom {:actions {} :store {}})
       {:name "c" :needs [] :produces ["z"] :run 42})
      (is false "expected throw")
      (catch clojure.lang.ExceptionInfo e
        (is (str/includes? (str (.getMessage e)) ":op :eval"))
        (is (str/includes? (str (.getMessage e)) "received :run: 42"))))))
