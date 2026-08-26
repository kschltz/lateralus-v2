(ns kschltz.agent.loop.stall-test
  "Unit tests for ReAct stall detection and session-durable counters."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.loop.stall :as stall]))

(defn- add-lib-call
  [id require-ns]
  {:id id :type "function"
   :function {:name "clojure_add_lib"
              :arguments (json/generate-string
                          {:lib "com.taoensso/nippy"
                           :require require-ns
                           :version "3.4.2"})}})

(defn- echo-call
  [id]
  {:id id :type "function"
   :function {:name "echo" :arguments "{\"msg\":\"ok\"}"}})

(defn- add-lib-fail
  [call]
  {:call call
   :result (json/generate-string
            {:status :ok
             :loaded? false
             :added ["com.taoensso/nippy"]
             :required-error "CompilerException"})})

(defn- echo-ok
  [call]
  {:call call :result "ok"})

(deftest seed-from-state-copies-missing-counters
  (let [ctx (stall/seed-from-state
             {:agent/state {:agent/last-tool-shape #{["clojure_add_lib" "{:lib \"x\"}"]}
                            :agent/shape-err-count 1
                            :agent/shape-err-counts {["clojure_add_lib" "{:lib \"x\"}"] 1}}
              :agent/shape-err-count 0})]
    (is (= 0 (:agent/shape-err-count ctx))
        "ctx value wins when already present")
    (is (= #{["clojure_add_lib" "{:lib \"x\"}"]}
           (:agent/last-tool-shape ctx)))
    (is (= {["clojure_add_lib" "{:lib \"x\"}"] 1}
           (:agent/shape-err-counts ctx)))))

(deftest persist-writes-state-delta
  (let [out (stall/persist {:agent/state-delta {:kept true}}
                           {:agent/shape-err-count 2})]
    (is (true? (:kept (:agent/state-delta out))))
    (is (= 2 (:agent/shape-err-count out)))
    (is (= 2 (get-in out [:agent/state-delta :agent/shape-err-count])))))

(deftest decide-exact-stall-on-identical-sig
  (let [calls [(echo-call "tc1")]
        ctx {:tool/calls calls
             :tool/results [(echo-ok (first calls))]
             :agent/last-tool-call-sig (stall/tool-call-sig calls)}
        {:keys [action]} (stall/decide ctx)]
    (is (= :exact-stall action))))

(deftest decide-shape-stall-on-same-lib-varying-require
  (testing "two all-error add-lib turns with the same :lib trip shape-stall"
    (let [c1 (add-lib-call "a" "ns1")
          first (stall/decide {:tool/calls [c1]
                               :tool/results [(add-lib-fail c1)]})
          seeded (merge {:tool/calls [(add-lib-call "b" "ns2")]
                         :tool/results [(add-lib-fail (add-lib-call "b" "ns2"))]}
                        (:patch first))
          second (stall/decide seeded)]
      (is (= :continue (:action first)))
      (is (= :shape-stall (:action second)))
      (is (= 2 (get-in second [:patch :agent/shape-err-count]))))))

(deftest decide-primary-count-trips-when-sibling-succeeds
  (testing "add-lib loaded?=false twice trips even when echo succeeds on each turn"
    (let [a1 (add-lib-call "a" "ns1")
          e1 (echo-call "e1")
          first (stall/decide {:tool/calls [a1 e1]
                               :tool/results [(add-lib-fail a1) (echo-ok e1)]})
          a2 (add-lib-call "b" "ns2")
          e2 (echo-call "e2")
          second (stall/decide (merge {:tool/calls [a2 e2]
                                       :tool/results [(add-lib-fail a2) (echo-ok e2)]}
                                      (:patch first)))]
      (is (= :continue (:action first))
          "first mixed turn must not stall")
      (is (= :shape-stall (:action second))
          "second add-lib failure must stall via primary-arg count")
      (is (>= (get-in second [:patch :agent/shape-err-counts
                              (stall/tool-call-shape a2)]
                      0)
              2)))))

(deftest decide-success-resets-primary-count
  (let [fail (add-lib-call "a" "ns1")
        after-fail (:patch (stall/decide {:tool/calls [fail]
                                          :tool/results [(add-lib-fail fail)]}))
        ok-call (add-lib-call "b" "ns-ok")
        ok-result {:call ok-call
                   :result (json/generate-string {:status :ok :loaded? true})}
        after-ok (stall/decide (merge {:tool/calls [ok-call]
                                       :tool/results [ok-result]}
                                      after-fail))]
    (is (= :continue (:action after-ok)))
    (is (zero? (get-in after-ok [:patch :agent/shape-err-counts
                                 (stall/tool-call-shape ok-call)])))))
