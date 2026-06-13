(ns kschltz.agent.exchange-test
  "Tests for the default exchange chain assembly."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.exchange :as exchange]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.interceptors.schema :as schema]
            [malli.core :as m]))

(deftest chain-loads-in-correct-order
  (testing "default chain has the locked stage order"
    (is (= [::ix/error-boundary
            ::ix/compose-context
            ::ix/llm-call
            ::ix/parse-response
            ::ix/dispatch
            ::ix/store-exchange
            ::ix/deliver-responses
            ::ix/notify]
           (mapv :name exchange/default-exchange-chain)))))

(deftest every-stage-validates-against-interceptor-schema
  (is (= :ok (ix/check-stages))))

(deftest default-chain-first-element-validates
  (is (not (m/explain schema/Interceptor
                      (first exchange/default-exchange-chain)))))

;; ---- End-to-end through chain/execute (with stub LLM) ----

(defn- run-exchange
  "Execute the default chain with a stub LlmClient. Returns final ctx."
  [user-text]
  (chain/execute
    {:agent/state        {:base-url "stub" :api-key nil :model "stub/v0"
                          :agent/system-message "you are a test agent"}
     :exchange/user-text user-text
     :exchange/session-id :test-session
     :exchange/user-msg-id (str (random-uuid))}
    exchange/default-exchange-chain))

(deftest full-exchange-returns-stub-response
  (let [out (run-exchange "hello world")]
    (is (string? (:exchange/response out)))
    (is (str/starts-with? (:exchange/response out)
                          "lateralus-v2 stub LLM echoed:"))
    (is (some? (:memory/last-exchange out))
        "store-exchange leave stage records the exchange")
    (is (= :test-session (-> out :memory/last-exchange :session-id))
        "session-id threads through the chain")))

(deftest full-exchange-delivers-response
  (let [out (run-exchange "ping")]
    (is (vector? (:exchange/delivered out)))
    (is (= 1 (count (:exchange/delivered out))))
    (is (= :test-session (-> out :exchange/delivered first :session-id)))))

(deftest full-exchange-notifies-once
  (let [out (run-exchange "ping")]
    (is (= 1 (count (:exchange/notified out))))
    (is (= :complete (-> out :exchange/notified first :event)))))

;; ---- Sequential tool execution (fact-sequential-tools) ----

(deftest dispatch-uses-sequential-mapv-by-default
  (testing "default state has no :agent/parallel-tools? → sequential"
    (let [enter-fn (:enter ix/dispatch)
          ctx      {:agent/state {}
                    :tool/calls [{:id "1" :name "fake"}
                                 {:id "2" :name "fake"}]}
          out      (enter-fn ctx)]
      (is (map? out) "dispatch enter returns a ctx")
      (is (vector? (:tool/results out)))
      (is (= 2 (count (:tool/results out)))
          "stub dispatch returns one result per call, sequentially"))))

;; ---- Decoupling verification (plan Step 3 risk) ----

(deftest interceptors-do-not-depend-on-loop-clj
  (testing "no alias or refer targets kschltz.agent.loop"
    (let [ns-symbol (find-ns 'kschltz.agent.interceptors)
          aliases  (ns-aliases ns-symbol)
          refs     (ns-refers ns-symbol)
          all-keys (map (comp str key) (concat aliases refs))
          bad      (filter #(str/starts-with? % "kschltz.agent.loop")
                           all-keys)]
      (is (empty? bad)
          (str "no alias or refer targets kschltz.agent.loop; found: " (vec bad))))))
