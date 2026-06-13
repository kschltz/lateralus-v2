(ns kschltz.lateralus-test
  (:require [clojure.test :refer [deftest is]]))

(deftest bootstrap-test
  (is (= "kschltz.lateralus" (name 'kschltz.lateralus))))
