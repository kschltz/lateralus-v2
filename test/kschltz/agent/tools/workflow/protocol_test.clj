(ns kschltz.agent.tools.workflow.protocol-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.tools.workflow.protocol :as proto]))

(def valid-action
  {:name "A"
   :needs []
   :produces ["x"]
   :run {:op :literal :values {"x" 1}}})

(deftest action-schema-is-closed
  (is (proto/valid-action? valid-action))
  (is (not (proto/valid-action? (assoc valid-action :extra 1))))
  (is (not (proto/valid-action? (dissoc valid-action :produces))))
  (is (proto/valid-action? (assoc valid-action :needs [:x] :produces [:y]
                                 :run {:op "eval" :code "(fn [s] s)"}))))

(deftest run-result-schema
  (is (proto/valid-run-result?
       {:status :done :ran ["A"] :parallel-waves [["A"]]
        :store {"x" 1} :blocked? false :errors []}))
  (is (not (proto/valid-run-result? {:status :done}))))
