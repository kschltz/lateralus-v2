(ns kschltz.agent.loop.stall-test
  "Unit tests for ReAct stall detection and session-durable counters."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
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

(defn- tool-test-call
  [id args-json]
  {:id id :type "function"
   :function {:name "tool_test" :arguments args-json}})

(defn- tool-test-args-fail
  [call]
  {:call call
   :result (json/generate-string
            {:ok false
             :tool "tool_test"
             :phase "args"
             :error stall/tool-test-args-hint})})

(defn- tool-test-probe
  [call]
  {:call call
   :result (json/generate-string
            {:ok false
             :tool "tool_test"
             :phase "probe"
             :actual "<html>ticks</html>"})})

(deftest result-error-shape-treats-tool-test-args-not-probe
  (is (true? (stall/result-error-shape?
               (tool-test-args-fail (tool-test-call "t" "{\"name\":\"ws\"}")))))
  (is (false? (stall/result-error-shape?
                (tool-test-probe (tool-test-call "t" "{\"name\":\"ws\"}")))))
  (is (true? (stall/result-error-shape?
               {:call {:function {:name "tool_test"}}
                :result "Tool 'tool_test' input validation failed: {:args [\"invalid type\"]}"}))))

(deftest decide-shape-stall-on-repeated-tool-test-args
  (let [c1 (tool-test-call "t1" "{\"name\":\"ws_live\",\"args\":\"ws://x\"}")
        first (stall/decide {:tool/calls [c1]
                              :tool/results [(tool-test-args-fail c1)]})
        c2 (tool-test-call "t2" "{\"name\":\"ws_live\",\"args\":\"ws://y\"}")
        second (stall/decide (merge {:tool/calls [c2]
                                      :tool/results [(tool-test-args-fail c2)]}
                                     (:patch first)))]
    (is (= :continue (:action first)))
    (is (= :shape-stall (:action second)))
    (is (>= (get-in second [:patch :agent/shape-err-counts
                             (stall/tool-call-shape c2)]
                    0)
            2))))

(deftest decide-does-not-stall-on-repeated-tool-test-probe
  (let [c1 (tool-test-call "t1" "{\"name\":\"ws_live\"}")
        first (stall/decide {:tool/calls [c1]
                              :tool/results [(tool-test-probe c1)]})
        c2 (tool-test-call "t2" "{\"name\":\"ws_live\",\"args\":{}}")
        second (stall/decide (merge {:tool/calls [c2]
                                      :tool/results [(tool-test-probe c2)]}
                                     (:patch first)))]
    (is (= :continue (:action first)))
    (is (= :continue (:action second)))
    (is (zero? (get-in second [:patch :agent/shape-err-counts
                                (stall/tool-call-shape c2)]
                       0)))))

(deftest inject-tool-test-args-hint-on-args-failure
  (let [call (tool-test-call "t1" "{\"name\":\"ws_live\",\"args\":\"ws://x\"}")
        ctx {:llm/request {:messages []}
             :tool/results [(tool-test-args-fail call)]}
        out (stall/inject-tool-test-args-hint ctx)
        msg (peek (get-in out [:llm/request :messages]))]
    (is (= "system" (:role msg)))
    (is (str/includes? (:content msg) "JSON object"))
    (is (str/includes? (:content msg) "file_read")
        "hint tells the model not to inspect factory source")))

