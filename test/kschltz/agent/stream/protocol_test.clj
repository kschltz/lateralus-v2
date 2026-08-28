(ns kschltz.agent.stream.protocol-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.stream.protocol :as protocol]))

(deftest stream-bus?-checks-protocol
  (let [bus (reify protocol/StreamBus)]
    (is (protocol/stream-bus? bus))
    (is (not (protocol/stream-bus? {})))))
