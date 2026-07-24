(ns kschltz.agent.tools.mcp.protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tools.mcp.protocol :as proto]
            [kschltz.agent.tools.mcp.test-util :as tu]))

(deftest client?-detects-implementers
  (let [c (tu/fake-loopback-client)]
    (is (proto/client? c))
    (is (not (proto/client? {})))
    (proto/close! c)))

(deftest server-info-never-raises
  (let [c (tu/fake-loopback-client)
        info (proto/-server-info c)]
    (is (map? info))
    (is (true? (:initialized? info)))
    (is (= "fake-mcp-server" (:name info)))
    (proto/close! c)))

(deftest protocol-methods-dispatch
  (testing "list and call over loopback fake server"
    (let [c (tu/fake-loopback-client)
          tools (proto/-list-tools c)
          names (set (map :name tools))]
      (is (contains? names "echo"))
      (is (contains? names "add"))
      (is (contains? names "fail"))
      (let [echo (proto/-call-tool c "echo" {:message "hi"})]
        (is (false? (:isError echo)))
        (is (= "hi" (get-in echo [:content 0 :text]))))
      (let [fail (proto/-call-tool c "fail" {:reason "nope"})]
        (is (true? (:isError fail))))
      (proto/close! c)
      (is (true? (:closed? (proto/-server-info c)))
          "after close, info still returns without raising"))))
