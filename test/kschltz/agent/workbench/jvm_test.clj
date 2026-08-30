(ns kschltz.agent.workbench.jvm-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.workbench.jvm :as jvm]))

(deftest available-predicate-is-boolean
  (is (boolean? (jvm/available?))))

(deftest handler-ops-exposes-secrets-as-top-level-http-capability
  (let [ops (jvm/handler-ops
             {:workbench-ref (atom nil)
              :runtime-atom (atom nil)
              :secret-store ::sealed-store
              :session-store nil
              :hub nil})]
    (is (map? (:settings-ops ops)))
    (is (map? (:secret-ops ops)))
    (is (nil? (get-in ops [:settings-ops :secret-ops]))
        "HTTP destructures :secret-ops beside, not inside, :settings-ops")))
