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
  (let [p (with-meta
            [{:name :recall :slot :enrich :enter identity}
             {:name :store :slot :persist :leave identity}]
            {:plugin/name :memory})]
    (is (nil? (plugin/validate-plugins [p])))))

(deftest invalid-plugin-fails-schema
  (testing "non-keyword name fails"
    (let [bad [(with-meta [{:name "not-a-keyword" :slot :enrich :enter identity}]
                 {:plugin/name :bad})]
          result (plugin/validate-plugins bad)]
      (is (map? result) "returns a :problems/:message map")
      (is (vector? (:problems result)))
      (is (string? (:message result)))))
  (testing "empty map fails"
    (let [result (plugin/validate-plugins [(with-meta [{}] {:plugin/name :bad})])]
      (is (map? result))
      (is (seq (:problems result))))))

;; ---- assemble-chain: deterministic ----

(deftest assemble-chain-is-deterministic
  (testing "same plugins in same order produce same chain"
    (let [p1 (with-meta [{:name :a-recall :slot :enrich :enter identity}]
               {:plugin/name :a})
          p2 (with-meta [{:name :b-recall :slot :enrich :enter identity}]
               {:plugin/name :b})]
      (is (= (plugin/assemble-chain [p1 p2])
             (plugin/assemble-chain [p1 p2]))))))

(deftest assemble-chain-order-matters
  (testing "plugin order affects chain order when plugins contribute the same slot"
    (let [p1 (with-meta [{:name :a-guard :slot :guard :enter identity}]
               {:plugin/name :a})
          p2 (with-meta [{:name :b-guard :slot :guard :enter identity}]
               {:plugin/name :b})]
      (is (= [:a-guard :b-guard] (mapv :name (plugin/assemble-chain [p1 p2])))
          "declaration order yields a-guard before b-guard")
      (is (= [:b-guard :a-guard] (mapv :name (plugin/assemble-chain [p2 p1])))
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
          (is (re-find #"Invalid plugin" (.getMessage e))))))))

;; ---- Interceptor shape ----

(deftest assembled-interceptors-have-plugin-metadata
  (let [p (with-meta [{:name :recall :slot :enrich :enter identity}]
            {:plugin/name :memory})
        chain (plugin/assemble-chain [p])
        ix (first chain)]
    (is (= :recall (:name ix)))
    (is (= :memory (:plugin/name ix)))
    (is (= :enrich (:plugin/slot ix)))
    (is (m/validate schema/Interceptor ix))))

;; ---- Slotless plugin appended after slotted ----

(deftest slotless-plugin-is-appended-after-slotted
  (let [p1 (with-meta [{:name :recall :slot :enrich :enter identity}]
             {:plugin/name :memory})
        p2 (with-meta [{:name :standalone :enter identity}]
             {:plugin/name :custom})
        chain (plugin/assemble-chain [p1 p2])]
    (is (= [:recall :standalone] (mapv :name chain)))))

;; ---- MVP empty default registry ----

(deftest empty-plugins-produce-empty-chain
  (testing "MVP default: no plugins, no chain"
    (is (= [] (plugin/assemble-chain [])))))

;; ---- Integration: plugin interceptors run via chain ----

(deftest assembled-interceptor-executes-via-chain
  (let [calls (atom [])
        p (with-meta [{:name :rec
                       :slot :enrich
                       :enter (fn [ctx]
                                (swap! calls conj :enter)
                                ctx)}
                      {:name :rec2
                       :slot :enrich
                       :enter (fn [ctx]
                                (swap! calls conj :enter2)
                                ctx)}]
            {:plugin/name :test})]
    (let [chain (plugin/assemble-chain [p])
          out   (chain/execute {} chain)]
      (is (= [:enter :enter2] @calls))
      (is (map? out) "chain returns a clean ctx"))))

;; ---- all-nil stage interceptor is rejected at assemble time ----

(deftest assemble-chain-rejects-all-nil-stages
  (testing "throws ex-info with :plugin/name, :index, :interceptor"
    (let [p (with-meta [{:name :typo-stage :slot :guard}]
              {:plugin/name :bad-stub})]
      (try
        (plugin/assemble-chain [p])
        (is false "expected throw")
        (catch clojure.lang.ExceptionInfo e
          (let [d (ex-data e)]
            (is (= :bad-stub (:plugin/name d)))
            (is (= 0 (:index d)))
            (is (re-find #"no stage fn" (.getMessage e)))))))))

;; ---- non-map interceptor is rejected explicitly ----

(deftest assemble-chain-rejects-non-map-ix
  (testing "non-map interceptor fails Malli validation before assemble"
    (doseq [bad-ix [nil "string" :keyword [1 2 3] 42]]
      (let [p (with-meta [bad-ix] {:plugin/name :bad-shape})]
        (try
          (plugin/assemble-chain [p])
          (is false (str "expected throw for ix=" (pr-str bad-ix)))
          (catch clojure.lang.ExceptionInfo e
            (let [d (ex-data e)]
              (is (vector? (:problems d)))
              (is (seq (:problems d)))
              (is (= [p] (:plugins d)))
              (is (re-find #"Invalid plugin" (.getMessage e))))))))))

;; ---- unknown slot is rejected ----

(deftest assemble-chain-rejects-unknown-slot
  (let [p (with-meta [{:name :bad :slot :not-a-slot :enter identity}]
            {:plugin/name :bad})]
    (try
      (plugin/assemble-chain [p])
      (is false "expected throw")
      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)]
          (is (= [:not-a-slot] (:slots d)))
          (is (= plugin/default-slot-order (:allowed d))))))))

;; ---- slot sorting matches default-slot-order ----

(deftest assemble-chain-sorts-slots-by-default-order
  (let [p (with-meta
            [{:name :notify-ix :slot :notify :leave identity}
             {:name :guard-ix :slot :guard :enter identity}
             {:name :enrich-ix :slot :enrich :enter identity}]
            {:plugin/name :sorter})]
    (is (= [:guard-ix :enrich-ix :notify-ix]
           (mapv :name (plugin/assemble-chain [p]))))))

;; ---- explain-errors returns nil or a non-empty vector (docstring honesty) ----

(deftest explain-errors-shape
  (testing "nil explain-result → nil"
    (is (nil? (#'plugin/explain-errors nil))))
  (testing "empty :errors → nil"
    (is (nil? (#'plugin/explain-errors {:errors []}))))
  (testing "non-empty :errors → non-empty vector"
    (let [problems [{:type :missing-key}]]
      (is (= problems (#'plugin/explain-errors {:errors problems}))))))
