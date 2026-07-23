(ns kschltz.agent.workbench.loop-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.workbench.loop :as loop]))

(deftest run-session-var-exists
  (is (fn? loop/run-session!)))
