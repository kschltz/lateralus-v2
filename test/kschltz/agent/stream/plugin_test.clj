(ns kschltz.agent.stream.plugin-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.llm.client :as llm]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.stream.bus :as bus]
            [kschltz.agent.stream.plugin :as stream.plugin]))

(deftest plugin-records-stub-exchange
  (let [b     (bus/create-bus)
        p     (stream.plugin/stream-plugin b)
        chain (plugin/assemble-chain [(plugins.base/base-plugin) p])
        rt    (runtime/start {:exchange-chain chain
                              :agent/llm-client (llm/stub-client)
                              :initial-state {:agent/system-message "sys"
                                              :model "stub/v0"}}
                             "stream-plugin-test")
        result (runtime/send-message rt "hello there")
        tid    (:stream/turn-id result)
        snap   (bus/snapshot b tid)]
    (is (string? tid))
    (is (= "done" (:status snap)))
    (is (re-find #"hello there" (:text snap)))
    (is (some #{"text-delta"} (map :type (:events snap))))
    (is (some #{"llm-done"} (map :type (:events snap))))))

(deftest second-exchange-opens-a-new-turn
  (let [b     (bus/create-bus)
        p     (stream.plugin/stream-plugin b)
        chain (plugin/assemble-chain [(plugins.base/base-plugin) p])
        rt    (runtime/start {:exchange-chain chain
                              :agent/llm-client (llm/stub-client)
                              :initial-state {:agent/system-message "sys"
                                              :model "stub/v0"}}
                             "stream-plugin-test-2")
        a (:stream/turn-id (runtime/send-message rt "one"))
        b-id (:stream/turn-id (runtime/send-message rt "two"))]
    (is (string? a))
    (is (string? b-id))
    (is (not= a b-id))
    (is (= "done" (:status (bus/snapshot b a))))
    (is (= "done" (:status (bus/snapshot b b-id))))))

(deftest empty-plugin-when-bus-nil
  (is (= [] (stream.plugin/stream-plugin nil))))
