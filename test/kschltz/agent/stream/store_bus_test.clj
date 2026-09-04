(ns kschltz.agent.stream.store-bus-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.llm.stream :as llm.stream]
            [kschltz.agent.stream.bus :as bus]
            [kschltz.agent.stream.store-bus :as store-bus]
            [kschltz.agent.store.memory :as mem]))

(deftest historic-snapshot-after-fresh-bus
  (let [engine (mem/memory-store)
        live (store-bus/store-bus engine)
        id (bus/open-turn! live {:session-id "s1" :user-text "hi"})]
    (bus/emit! live id (llm.stream/event :thinking-delta {:thinking "hmm"}))
    (bus/emit! live id (llm.stream/event :text-delta {:text "hello"}))
    (bus/emit! live id (llm.stream/event :tool-call {:tool-name "portal_submit"}))
    (bus/close-turn! live id :done {})
    (is (= "hello" (:text (bus/snapshot live id))))
    (let [historic (store-bus/store-bus engine)
          snap (bus/snapshot historic id)
          chunk (bus/events-since historic id -1)]
      (is (nil? (bus/current-id historic)))
      (is (= id (bus/latest-id historic)))
      (is (= "hello" (:text snap)))
      (is (= "hmm" (:thinking snap)))
      (is (= "hi" (:user-text snap)))
      (is (= "s1" (:session-id snap)))
      (is (= "done" (:status snap)))
      (is (false? (:live? snap)))
      (is (= ["portal_submit"] (:tool-names snap)))
      (is (= 3 (count (:events snap))))
      (is (= 3 (count (:events chunk))))
      (is (nil? (bus/snapshot historic "missing"))))))

(deftest live-path-still-works
  (let [b (store-bus/store-bus (mem/memory-store))
        id (bus/open-turn! b {:user-text "x"})]
    (is (= id (bus/current-id b)))
    (bus/emit! b id (llm.stream/event :text-delta {:text "a"}))
    (is (true? (:live? (bus/snapshot b id))))
    (bus/close-turn! b id :done {})
    (is (nil? (bus/current-id b)))
    (is (= "done" (:status (bus/snapshot b id))))))
