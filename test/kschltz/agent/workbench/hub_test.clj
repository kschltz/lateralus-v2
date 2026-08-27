(ns kschltz.agent.workbench.hub-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.stream.bus :as stream.bus]
            [kschltz.agent.workbench.hub :as hub]))

(deftest publish-and-snapshot
  (let [h (hub/create-hub {:session-id "s1"})]
    (hub/publish-turn! h {:role :system :text "hello"})
    (let [snap (hub/snapshot h)]
      (is (= "s1" (:session-id snap)))
      (is (= 1 (count (:turns snap))))
      (is (= :system (:role (first (:turns snap)))))
      (is (pos? (:rev snap))))))

(deftest refs-strip-values-in-snapshot
  (let [h (hub/create-hub {})
        ref (hub/put-ref! h {:label "t" :preview "{:a 1}" :value {:a 1}})]
    (is (string? (:id ref)))
    (is (nil? (:value ref)))
    (is (some? (:value (hub/get-ref h (:id ref)))))
    (is (nil? (get-in (hub/snapshot h) [:refs (:id ref) :value])))))

(deftest await-human-roundtrip
  (let [h (hub/create-hub {})]
    (future (Thread/sleep 50)
            (hub/enqueue-human! h {:text "ping" :refs []}))
    (let [msg (hub/await-human! h {:timeout-ms 2000})]
      (is (= "ping" (:text msg)))
      (is (= :user (:role (last (:turns (hub/snapshot h))))))
      (is (= :running (:status (hub/snapshot h)))))))

(deftest enqueue-sets-queued-and-publishes-user
  (let [h (hub/create-hub {})]
    (hub/enqueue-human! h {:text "hi" :refs []})
    (let [snap (hub/snapshot h)]
      (is (= :queued (:status snap)))
      (is (= :user (:role (last (:turns snap)))))
      (is (= "hi" (:text (last (:turns snap))))))))

(deftest snapshot-exposes-current-turn-id
  (let [b (stream.bus/create-bus)
        id (stream.bus/open-turn! b {:user-text "q"})
        h (hub/create-hub {:stream-bus b})]
    (is (= id (:current-turn-id (hub/snapshot h))))
    (stream.bus/close-turn! b id :done {})
    (is (nil? (:current-turn-id (hub/snapshot h))))))

(deftest await-human-opens-live-turn
  (let [b (stream.bus/create-bus)
        h (hub/create-hub {:stream-bus b})]
    (hub/enqueue-human! h {:text "go" :refs []})
    (let [msg (hub/await-human! h {:timeout-ms 500})
          snap (hub/snapshot h)]
      (is (= "go" (:text msg)))
      (is (= :running (:status snap)))
      (is (string? (:current-turn-id snap)))
      (is (true? (:live? (stream.bus/snapshot b (:current-turn-id snap))))))))

(deftest format-prompt-with-refs
  (testing "plain"
    (is (= "hi" (hub/format-prompt {:text "hi" :refs []}))))
  (testing "with refs"
    (let [s (hub/format-prompt
             {:text "look"
              :refs [{:id "abc" :preview "{:x 1}" :label "table"}]})]
      (is (re-find #"@portal/abc" s))
      (is (re-find #"table" s)))))
