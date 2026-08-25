(ns kschltz.agent.tools.mcp.transport-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.tools.mcp.protocol :as proto]
            [kschltz.agent.tools.mcp.transport :as transport]))

(deftest loopback-transport-implements-request-response-lifecycle
  (let [t (transport/loopback-transport
           #(assoc % :result {:echo (:params %)}))]
    (is (proto/-alive? t))
    (proto/-send! t {:id 1 :params {:value "x"}})
    (is (= {:id 1
            :params {:value "x"}
            :result {:echo {:value "x"}}}
           (proto/-recv! t 100)))
    (proto/-close-transport! t)
    (is (not (proto/-alive? t)))))

(deftest loopback-timeout-is-structured
  (let [t (transport/loopback-transport (constantly nil))]
    (try
      (proto/-send! t {:method "notify"})
      (proto/-recv! t 1)
      (is false "receive should time out")
      (catch clojure.lang.ExceptionInfo e
        (is (= :timeout (:phase (ex-data e)))))
      (finally
        (proto/-close-transport! t)))))
