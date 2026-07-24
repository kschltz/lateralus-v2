(ns kschltz.agent.tools.mcp.client-test
  (:require [clojure.test :refer [deftest is testing]]
            [fake-mcp-server :as fake]
            [kschltz.agent.tools.mcp.client :as client]
            [kschltz.agent.tools.mcp.protocol :as proto]
            [kschltz.agent.tools.mcp.transport :as transport]))

(deftest handshake-and-round-trip
  (let [t (transport/loopback-transport fake/handle-message)
        c (client/make-client t {:server-id "fake"})]
    (is (= "fake-mcp-server" (:name (proto/-initialize! c))))
    (is (>= (count (proto/-list-tools c)) 2))
    (is (= "42.0"
           (get-in (proto/-call-tool c "add" {:a 20 :b 22})
                   [:content 0 :text])))
    (proto/close! c)
    (proto/close! c) ;; idempotent
    (is (thrown-with-msg? Exception #"closed"
                          (proto/-list-tools c)))))

(deftest timeout-phase
  (testing "recv timeout surfaces :phase :timeout"
    (let [t (reify proto/McpTransport
              (-send! [_ _])
              (-recv! [_ _]
                (throw (ex-info "MCP transport recv timed out"
                                {:phase :timeout})))
              (-close-transport! [_])
              (-alive? [_] true))
          c (client/make-client t {:server-id "x" :startup-timeout-ms 10})]
      (try
        (proto/-initialize! c)
        (is false "expected timeout")
        (catch Exception e
          (is (= :timeout (:phase (ex-data e)))))))))

(deftest close-is-idempotent
  (let [closed (atom 0)
        t (reify proto/McpTransport
            (-send! [_ _])
            (-recv! [_ _] {:jsonrpc "2.0" :id 1
                           :result {:protocolVersion "2024-11-05"
                                    :capabilities {}
                                    :serverInfo {:name "x" :version "0"}}})
            (-close-transport! [_] (swap! closed inc))
            (-alive? [_] true))
        c (client/make-client t {:server-id "x"})]
    ;; initialize consumes one recv
    (proto/-initialize! c)
    (proto/close! c)
    (proto/close! c)
    (is (= 1 @closed))))
