(ns kschltz.agent.workbench.jvm-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.workbench.jvm :as jvm]))

(deftest available-predicate-is-boolean
  (is (boolean? (jvm/available?))))
