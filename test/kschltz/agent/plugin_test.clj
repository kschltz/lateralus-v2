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

;; ---- all-nil stage interceptor is rejected at assemble time ----

(deftest assemble-chain-rejects-all-nil-stages
  (testing "throws ex-info with :plugin/name, :plugin/slot, :interceptor"
    (let [p {:plugin/name :bad-stub
             :plugin/slots {:guard [{:name :typo-stage}]}}]
      (try
        (plugin/assemble-chain [p])
        (is false "expected throw")
        (catch clojure.lang.ExceptionInfo e
          (let [d (ex-data e)]
            (is (= :bad-stub (:plugin/name d)))
            (is (= :guard (:plugin/slot d)))
            (is (re-find #"no stage fn" (.getMessage e)))))))))

;; ---- non-map slot interceptor is rejected explicitly ----

(deftest assemble-chain-rejects-non-map-ix
  (testing "throws ex-info with 'must be a map' for non-map slot entries"
    (doseq [bad-ix [nil "string" :keyword [1 2 3] 42]]
      (let [p {:plugin/name :bad-shape
               :plugin/slots {:guard [bad-ix]}}]
        (try
          (plugin/assemble-chain [p])
          (is false (str "expected throw for ix=" (pr-str bad-ix)))
          (catch clojure.lang.ExceptionInfo e
            (let [d (ex-data e)]
              (is (= :bad-shape (:plugin/name d)))
              (is (= :guard (:plugin/slot d)))
              (is (= bad-ix (:interceptor d)))
              (is (re-find #"must be a map" (.getMessage e))))))))))

;; ---- :plugin/original-name is omitted when input has no :name ----

(deftest assembled-interceptor-omits-original-name-when-absent
  (testing "input interceptor with no :name produces no :plugin/original-name key"
    (let [p      {:plugin/name :foo
                  :plugin/slots {:enrich [{:enter identity}]}}   ; no :name
          chain  (plugin/assemble-chain [p])
          ix     (first chain)]
      (is (not (contains? ix :plugin/original-name))
          ":plugin/original-name is absent (not nil) when input has no :name"))))

(deftest plugin-register-fn-is-not-invoked
  (testing ":plugin/register is no longer in the schema and is not
   invoked at assemble time. The fn value is simply ignored."
    (let [register-called? (atom false)
          p                {:plugin/name :probe
                            :plugin/slots {:enrich [{:name :rec :enter identity}]}
                            :plugin/register (fn [_state _tools]
                                                (reset! register-called? true)
                                                {})}
          _                (plugin/assemble-chain [p])]
      (is (false? @register-called?)
          ":plugin/register is never invoked; the schema is open so the
           key is silently ignored, but a future plugin author relying
           on it would never see it fire. Bring it back when a real
           plugin lifecycle is added."))))

;; ---- explain-errors returns nil or a non-empty vector (docstring honesty) ----

(deftest explain-errors-shape
  (testing "nil explain-result → nil"
    (is (nil? (#'plugin/explain-errors nil))))
  (testing "empty :errors → nil"
    (is (nil? (#'plugin/explain-errors {:errors []}))))
  (testing "non-empty :errors → non-empty vector"
    (let [problems [{:type :missing-key}]]
      (is (= problems (#'plugin/explain-errors {:errors problems}))))))
