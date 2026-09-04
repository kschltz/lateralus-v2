(ns kschltz.agent.store.protocol-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.store.memory :as memory]
            [kschltz.agent.store.protocol :as proto]))

(deftest memory-store-satisfies-protocol
  (let [e (memory/memory-store)]
    (is (proto/store-engine? e))
    (is (nil? (proto/-close e)))))
