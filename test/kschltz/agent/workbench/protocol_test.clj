(ns kschltz.agent.workbench.protocol-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.workbench.protocol :as proto]))

(deftest workbench-protocol-exists
  (is (some? proto/Workbench)))
