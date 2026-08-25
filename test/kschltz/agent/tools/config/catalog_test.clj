(ns kschltz.agent.tools.config.catalog-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.tools.config.catalog :as catalog]))

(deftest stub-catalog-is-protocol-bound-and-deterministic
  (let [c (catalog/stub-catalog ["a" "b"])]
    (is (catalog/model-catalog? c))
    (is (= ["a" "b"]
           (catalog/list-models c
                                {:base-url "http://offline"
                                 :api-key nil})))))

(deftest catalog-wrapper-enforces-malli-input
  (let [c (catalog/stub-catalog)]
    (is (thrown? Exception
                 (catalog/list-models c {:base-url 42})))))
