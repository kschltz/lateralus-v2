(ns kschltz.agent.tools.clojure-runtime-test
  "Tests for the Clojure runtime prototyping tools."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.clojure-runtime :as rt]
            [kschltz.agent.tools.clojure-runtime.impl :as impl]
            [kschltz.agent.tools.clojure-runtime.protocol :as protocol]))

(def ^:private session-ctx
  {:exchange/session-id "test-session-001"})

(defn- invoke [registry name args]
  (json/parse-string (tool/invoke-tool (get registry name) args session-ctx) true))

(defn- invoke-raw [registry name args]
  (tool/invoke-tool (get registry name) args session-ctx))

(deftest registry-contains-five-runtime-tools
  (let [registry (rt/clojure-runtime-registry)]
    (is (= 5 (count registry)))
    (is (contains? registry "clojure/eval"))
    (is (contains? registry "clojure/add-lib"))
    (is (contains? registry "clojure/add-libs"))
    (is (contains? registry "clojure/sync-deps"))
    (is (contains? registry "clojure/repl-reset"))
    (is (every? tool/tool? (vals registry)))))

(deftest eval-returns-value-and-persists-definitions
  (let [reg (rt/clojure-runtime-registry)]
    (testing "simple expression"
      (let [r (invoke reg "clojure/eval" {:code "(+ 1 2)"})]
        (is (= "3" (:value r)))
        (is (= "number" (:type r)))
        (is (= 1 (:forms-evaluated r)))))
    (testing "definitions persist in session"
      (invoke reg "clojure/eval" {:code "(def my-x 42)"})
      (let [r (invoke reg "clojure/eval" {:code "my-x"})]
        (is (= "42" (:value r)))))
    (testing "stdout capture"
      (let [r (invoke reg "clojure/eval" {:code "(println \"hi\")"})]
        (is (= "nil" (:value r)))
        (is (= "hi\n" (:stdout r)))))))

(deftest repl-reset-clears-session-definitions
  (let [reg (rt/clojure-runtime-registry)]
    (invoke reg "clojure/eval" {:code "(def gone 1)"})
    (invoke reg "clojure/repl-reset" {})
    (let [raw (invoke-raw reg "clojure/eval" {:code "gone"})]
      (is (str/includes? raw "\"phase\":\"eval\"")))))

(deftest add-lib-loads-json-library
  (let [reg (rt/clojure-runtime-registry)]
    (let [loaded (invoke reg "clojure/add-lib"
                         {:lib "org.clojure/data.json"
                          :coord {:mvn/version "2.5.0"}})]
      (is (vector? (:libs loaded)))
      (is (some #(str/includes? % "data.json") (:libs loaded))))
    (let [r (invoke reg "clojure/eval"
                    {:code "(require '[clojure.data.json :as json]) (json/write-str {:a 1})"})]
      (is (= "\"{\\\"a\\\":1}\"" (:value r))))))

(deftest add-libs-resolves-multiple-libraries
  (let [reg (rt/clojure-runtime-registry)]
    (let [loaded (invoke reg "clojure/add-libs"
                         {:libs {"org.clojure/data.json" {:mvn/version "2.5.0"}}})]
      (is (seq (:libs loaded))))))

(deftest sync-deps-loads-from-project-deps-edn
  (let [reg (rt/clojure-runtime-registry {:deps-edn-path "deps.edn"})]
    (let [loaded (invoke reg "clojure/sync-deps" {})]
      (is (vector? (:libs loaded)))
      (is (some #(str/includes? % "clojure") (:libs loaded))))))

(deftest disabled-runtime-returns-error-envelope
  (let [reg (rt/clojure-runtime-registry {:enabled? false})]
    (let [raw (invoke-raw reg "clojure/eval" {:code "(+ 1 1)"})]
      (is (str/includes? raw "\"phase\":\"disabled\"")))))

(deftest protocol-smoke-test
  (let [runtime (impl/runtime {})]
    (testing "eval through protocol"
      (let [r (protocol/-eval runtime {:session-id "p1" :code "(inc 1)"})]
        (is (= "2" (:value r)))))
    (testing "reset through protocol"
      (protocol/-eval runtime {:session-id "p1" :code "(def z 9)"})
      (protocol/-reset runtime {:session-id "p1"})
      (is (thrown? clojure.lang.ExceptionInfo
                   (protocol/-eval runtime {:session-id "p1" :code "z"}))))))
