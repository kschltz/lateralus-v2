(ns kschltz.agent.session.store-engine-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.session.protocol :as proto]
            [kschltz.agent.session.store-engine :as store-engine]
            [kschltz.agent.store.memory :as mem]
            [kschltz.agent.store.protocol :as store]))

(defn- make-store []
  (store-engine/store-session-store (mem/memory-store)))

(deftest catalog-roundtrip-keeps-payload
  (let [s (make-store)
        pub (proto/-upsert! s {:id "alpha"
                               :title "Alpha"
                               :turns [{:id "t1" :text "hi"}]
                               :refs {"r1" {:id "r1"}}
                               :agent-state {:n 1}})]
    (is (= "alpha" (:id pub)))
    (is (= "Alpha" (:title pub)))
    (is (true? (:active? pub)))
    (is (nil? (:turns pub)) "list/public view omits workspace payload")
    (is (= "alpha" (proto/-current-id s)))
    (let [full (proto/-get s "alpha")]
      (is (= [{:id "t1" :text "hi"}] (:turns full)))
      (is (= {"r1" {:id "r1"}} (:refs full)))
      (is (= {:n 1} (:agent-state full))))
    (proto/-upsert! s {:id "beta" :title "Beta"})
    (is (= "alpha" (proto/-current-id s))
        "later upsert does not steal current")
    (proto/-set-current! s "beta")
    (is (= "beta" (proto/-current-id s)))
    (is (= 2 (count (proto/-list s))))
    (is (true? (:active? (first (filter #(= "beta" (:id %)) (proto/-list s))))))
    (is (true? (proto/-delete! s "alpha")))
    (is (nil? (proto/-get s "alpha")))
    (is (= 1 (count (proto/-list s))))
    (is (= "beta" (proto/-current-id s)))))

(deftest delete-cascades-turns-and-events
  (let [engine (mem/memory-store)
        s (store-engine/store-session-store engine)]
    (proto/-upsert! s {:id "alpha" :title "Alpha"})
    (store/-insert! engine :turns {:id "t1" :session-id "alpha"})
    (store/-insert! engine :events {:turn-id "t1" :seq 0 :type "text" :payload "{}"})
    (is (true? (proto/-delete! s "alpha")))
    (is (empty? (store/-select engine :turns {:where {:session-id "alpha"}})))
    (is (empty? (store/-select engine :events {:where {:turn-id "t1"}}))))

(deftest rejects-bad-id
  (let [s (make-store)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (proto/-upsert! s {:id "../etc" :title "no"})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (proto/-set-current! s "nope")))))
