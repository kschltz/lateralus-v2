(ns kschltz.agent.plugin-test
  "Tests for the v2 plugin system. MVP ships with no tool plugins
   (empty default registry), so these tests cover the assembly +
   validation contract, not v1 plugins."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.interceptors.schema :as schema]
            [kschltz.agent.plugin :as plugin]
            [malli.core :as m]))

;; ---- Schema validation ----

(deftest valid-plugin-passes-schema
  (let [p {:plugin/name :memory
           :plugin/slots {:enrich [{:name :recall :enter identity}]
                          :persist [{:name :store :leave identity}]}}]
    (is (nil? (plugin/validate-plugins [p])))))

(deftest invalid-plugin-fails-schema
  (testing "non-keyword name fails"
    (let [bad [{:plugin/name "not-a-keyword" :plugin/slots {}}]
          result (plugin/validate-plugins bad)]
      (is (map? result) "returns a :problems/:message map")
      (is (vector? (:problems result)))
      (is (string? (:message result)))))
  (testing "empty map fails"
    (let [result (plugin/validate-plugins [{}])]
      (is (map? result))
      (is (seq (:problems result))))))

;; ---- assemble-chain: deterministic ----

(deftest assemble-chain-is-deterministic
  (testing "same plugins in same order produce same chain"
    (let [p1 {:plugin/name :a :plugin/slots {:enrich [{:name :a-recall :enter identity}]}}
          p2 {:plugin/name :b :plugin/slots {:enrich [{:name :b-recall :enter identity}]}}]
      (is (= (plugin/assemble-chain [p1 p2])
             (plugin/assemble-chain [p1 p2]))))))

(deftest assemble-chain-order-matters
  (testing "plugin order affects chain order when plugins contribute slots"
    (let [p1 {:plugin/name :a :plugin/slots {:guard [{:name :a-guard :enter identity}]}}
          p2 {:plugin/name :b :plugin/slots {:guard [{:name :b-guard :enter identity}]}}]
      (is (= [:a.guard :b.guard] (mapv :name (plugin/assemble-chain [p1 p2])))
          "declaration order yields a.guard before b.guard")
      (is (= [:b.guard :a.guard] (mapv :name (plugin/assemble-chain [p2 p1])))
          "reversed declaration order reverses chain"))))

(deftest assemble-chain-handles-empty-list
  (is (= [] (plugin/assemble-chain []))))

(deftest assemble-chain-fails-fast-on-bad-shape
  (testing "throws ex-info with :problems vector carrying Malli details"
    (try
      (plugin/assemble-chain [{:bad-shape true}])
      (is false "expected throw")
      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)]
          (is (vector? (:problems d)))
          (is (seq (:problems d)))
          (is (= [{:bad-shape true}] (:plugins d)))
          (is (re-find #"Invalid plugin map" (.getMessage e))))))))

;; ---- Interceptor shape ----

(deftest assembled-interceptors-have-plugin-metadata
  (let [p {:plugin/name :memory
           :plugin/slots {:enrich [{:name :recall :enter identity}]}}
        chain (plugin/assemble-chain [p])
        ix (first chain)]
    (is (= :memory.enrich (:name ix)))
    (is (= :memory (:plugin/name ix)))
    (is (= :enrich (:plugin/slot ix)))
    (is (= :recall (:plugin/original-name ix)))
    (is (m/validate schema/Interceptor ix))))

;; ---- Plugin with :plugin/chain ----

(deftest plugin-with-chain-is-appended-verbatim
  (let [p1 {:plugin/name :a
            :plugin/slots {:enrich [{:name :a-recall :enter identity}]}}
        p2 {:plugin/name :b
            :plugin/chain [{:name :standalone :enter identity}]}
        chain (plugin/assemble-chain [p1 p2])]
    (is (= [:a.enrich :standalone] (mapv :name chain)))))

;; ---- MVP empty default registry ----

(deftest empty-plugins-produce-empty-chain
  (testing "MVP default: no plugins, no chain"
    (is (= [] (plugin/assemble-chain [])))))

;; ---- Integration: plugin interceptors run via chain ----

(deftest assembled-interceptor-executes-via-chain
  (let [calls (atom [])
        p {:plugin/name :test
           :plugin/slots {:enrich [{:name :rec
                                   :enter (fn [ctx]
                                            (swap! calls conj :enter)
                                            ctx)}
                                  {:name :rec2
                                   :enter (fn [ctx]
                                            (swap! calls conj :enter2)
                                            ctx)}]}}
        chain (plugin/assemble-chain [p])
        out (chain/execute {} chain)]
    (is (= [:enter :enter2] @calls))
    (is (map? out) "chain returns a clean ctx")))
