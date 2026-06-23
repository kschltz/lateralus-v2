(ns kschltz.agent.tools.runtime.jvm-test
  "Tests for the JVM ClojureRuntime implementation.

   The eval path is local and deterministic, so it is exercised
   directly. Real runtime dependency loading touches the network and the
   Clojure CLI basis, so it lives behind a `^:e2e` tag and is excluded
   from the fast suite."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [kschltz.agent.tools.runtime.jvm :as jvm]
            [kschltz.agent.tools.runtime.protocol :as proto]
            [kschltz.agent.tools.runtime.schemas :as schemas])
  (:import [clojure.lang DynamicClassLoader]))

;; ---------------------------------------------------------------------------
;; eval-code
;; ---------------------------------------------------------------------------

(deftest eval-code-returns-last-value-and-form-count
  (testing "multiple forms are evaluated; value is the pr-str of the last"
    (let [res (jvm/eval-code 'kschltz.runtime-test.basic
                             "(+ 1 2) (* 3 4)" {} nil)]
      (is (m/validate schemas/EvalResult res))
      (is (= 2 (:forms res)))
      (is (= "12" (:value res)))
      (is (= "" (:output res)))
      (is (nil? (:error res))))))

(deftest eval-code-persists-state-across-calls
  (testing "a def in one call is visible to a later call in the same ns"
    (let [ns-sym 'kschltz.runtime-test.persist]
      (jvm/eval-code ns-sym "(def the-answer 42)" {} nil)
      (let [res (jvm/eval-code ns-sym "(* the-answer 2)" {} nil)]
        (is (nil? (:error res)))
        (is (= "84" (:value res)))))))

(deftest eval-code-captures-stdout
  (testing "anything printed to *out* is captured in :output"
    (let [res (jvm/eval-code 'kschltz.runtime-test.out
                             "(println \"hello\") :ok" {} nil)]
      (is (= "hello\n" (:output res)))
      (is (= ":ok" (:value res))))))

(deftest eval-code-reports-exception-without-raising
  (testing "a thrown exception is captured in :error, value is nil"
    (let [res (jvm/eval-code 'kschltz.runtime-test.err
                             "(/ 1 0)" {} nil)]
      (is (nil? (:value res)))
      (is (string? (:error res)))
      (is (str/includes? (:error res) "ArithmeticException")))))

(deftest eval-code-truncates-output
  (testing ":max-output-bytes caps captured stdout"
    (let [res (jvm/eval-code 'kschltz.runtime-test.trunc
                             "(dotimes [_ 100] (print \"x\"))"
                             {:max-output-bytes 10} nil)]
      (is (str/includes? (:output res) "output truncated"))
      (is (<= 10 (count (:output res)))))))

(deftest eval-code-enforces-timeout
  (testing "a slow evaluation is cancelled and reported as a timeout"
    (let [res (jvm/eval-code 'kschltz.runtime-test.timeout
                             "(Thread/sleep 10000) :never"
                             {:eval-timeout-ms 100} nil)]
      (is (nil? (:value res)))
      (is (string? (:error res)))
      (is (str/includes? (:error res) "timed out")))))

(deftest eval-code-runs-under-supplied-classloader
  (testing "eval-code accepts a DynamicClassLoader and still evaluates"
    (let [cl  (jvm/new-classloader)
          res (jvm/eval-code 'kschltz.runtime-test.cl "(+ 40 2)" {} cl)]
      (is (= "42" (:value res))))))

;; ---------------------------------------------------------------------------
;; loaded-libs* / new-classloader
;; ---------------------------------------------------------------------------

(deftest loaded-libs*-returns-sorted-strings
  (testing "loaded-libs* returns a sorted vector of lib name strings"
    (let [libs (jvm/loaded-libs*)]
      (is (vector? libs))
      (is (seq libs))
      (is (every? string? libs))
      (is (= libs (vec (sort libs))))
      (is (some #(= "clojure.test" %) libs)
          "libs required by this test ns should be present"))))

(deftest new-classloader-is-dynamic
  (testing "new-classloader returns a DynamicClassLoader instance"
    (is (instance? DynamicClassLoader (jvm/new-classloader)))))

;; ---------------------------------------------------------------------------
;; Instrumentation
;; ---------------------------------------------------------------------------

(deftest eval-code-is-malli-instrumented
  (testing "calling eval-code with a non-symbol ns violates the input
            schema and raises (instrumentation is active)"
    (is (thrown? Exception
                 (jvm/eval-code "not-a-symbol" "(+ 1 1)" {} nil)))))

;; ---------------------------------------------------------------------------
;; JvmRuntime deftype
;; ---------------------------------------------------------------------------

(deftest jvm-runtime-satisfies-protocol-and-evals
  (testing "jvm-runtime builds a ClojureRuntime that evaluates code"
    (let [rt (jvm/jvm-runtime {})]
      (is (proto/capabilities? rt))
      (is (= "3" (:value (proto/-eval rt "(+ 1 2)" {}))))
      (is (true? (:eval? (proto/-capabilities rt))))
      (is (true? (:network? (proto/-capabilities rt)))))))

(deftest jvm-runtime-honors-eval-ns-and-network-flag
  (testing "config :eval-ns selects the default namespace; :network? is
            surfaced in capabilities"
    (let [rt (jvm/jvm-runtime {:eval-ns "kschltz.runtime-test.cfgns"
                               :network? false})]
      (is (= "kschltz.runtime-test.cfgns"
             (:ns (proto/-eval rt "(def z 1)" {}))))
      (is (false? (:network? (proto/-capabilities rt)))))))

;; ---------------------------------------------------------------------------
;; Real runtime dependency loading (network + Clojure CLI basis required)
;; ---------------------------------------------------------------------------

(deftest ^:e2e add-libs-loads-real-dependency
  (testing "add-lib resolves a real Maven coordinate and the eval runtime
            can then require and use it"
    (let [rt    (jvm/jvm-runtime {})
          added (proto/-add-libs rt '{org.clojure/data.json {:mvn/version "2.5.0"}} {})]
      (is (nil? (:error added)) (str "add-libs error: " (:error added)))
      (is (some #(str/includes? % "data.json") (:added added)))
      (let [res (proto/-eval rt "(require '[clojure.data.json :as json]) (json/write-str {:a 1})" {})]
        (is (nil? (:error res)))
        (is (str/includes? (:value res) "a"))))))
