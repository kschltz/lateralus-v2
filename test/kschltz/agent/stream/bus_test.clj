(ns kschltz.agent.stream.bus-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.llm.stream :as llm.stream]
            [kschltz.agent.stream.bus :as bus]
            [kschltz.agent.stream.protocol :as proto]))

(deftest open-emit-snapshot-close
  (let [b (bus/create-bus)
        id (bus/open-turn! b {:session-id "s" :user-text "hi"})]
    (is (proto/stream-bus? b))
    (is (= id (bus/current-id b)))
    (bus/emit! b id (llm.stream/event :thinking-delta {:thinking "hmm"}))
    (bus/emit! b id (llm.stream/event :text-delta {:text "hello"}))
    (bus/emit! b id (llm.stream/event :tool-call {:tool-name "portal_submit"}))
    (let [snap (bus/snapshot b id)]
      (is (= "hi" (:user-text snap)))
      (is (= "hmm" (:thinking snap)))
      (is (= "hello" (:text snap)))
      (is (= ["portal_submit"] (:tool-names snap)))
      (is (true? (:live? snap)))
      (is (= 3 (count (:events snap)))))
    (bus/close-turn! b id :done {})
    (is (nil? (bus/current-id b)))
    (is (= "done" (:status (bus/snapshot b id))))
    (is (false? (:live? (bus/snapshot b id))))))

(deftest latest-id-survives-close
  (let [b (bus/create-bus)
        id (bus/open-turn! b {:user-text "keep"})]
    (bus/close-turn! b id :done {})
    (is (= id (bus/latest-id b)))
    (is (nil? (bus/current-id b)))))

(deftest events-since-skips-seen
  (let [b (bus/create-bus)
        id (bus/open-turn! b {:user-text "x"})]
    (bus/emit! b id (llm.stream/event :text-delta {:text "a"}))
    (bus/emit! b id (llm.stream/event :text-delta {:text "b"}))
    (let [chunk (bus/events-since b id 0)]
      (is (= 1 (count (:events chunk))))
      (is (= "b" (get-in chunk [:events 0 :text]))))))
