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

(deftest eval-code-emits-per-form-values-and-status
  (testing "audit 2026-07 rec #4: every form's value is returned in :values
            (not just the last), and :status is :ok on clean eval"
    (let [res (jvm/eval-code 'kschltz.runtime-test.values
                             "(def x 1) (+ x 2) :done" {} nil)]
      (is (m/validate schemas/EvalResult res))
      (is (= :ok (:status res)))
      (is (= ["#'kschltz.runtime-test.values/x" "3" ":done"] (:values res))
          "all three form values survive in order (def returns the var)")
      (is (= ":done" (:value res))
          ":value stays the pr-str of the last form")))
  (testing "audit 2026-07 rec #3: an eval error sets :status :error and fills
            :error-detail with class/message/trace, while :error stays one-line"
    (let [res (jvm/eval-code 'kschltz.runtime-test.errstat "(/ 1 0)" {} nil)]
      (is (= :error (:status res)))
      (is (string? (:error res)))
      (is (str/includes? (:error res) "ArithmeticException"))
      (is (= "java.lang.ArithmeticException" (:class (:error-detail res))))
      (is (string? (:message (:error-detail res))))
      (is (pos? (count (:trace (:error-detail res)))))))
  (testing ":status is :truncated and :truncated? true when stdout is clipped"
    (let [res (jvm/eval-code 'kschltz.runtime-test.truncstat
                             "(dotimes [_ 100] (print \"x\"))"
                             {:max-output-bytes 10} nil)]
      (is (= :truncated (:status res)))
      (is (true? (:truncated? res)))
      (is (str/includes? (:output res) "output truncated"))))
  (testing ":status is :timeout on a runaway eval"
    (let [res (jvm/eval-code 'kschltz.runtime-test.timeoutstat
                             "(Thread/sleep 10000) :never"
                             {:eval-timeout-ms 100} nil)]
      (is (= :timeout (:status res)))
      (is (str/includes? (:error res) "timed out")))))

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

;; ---- verify-round-3 FIX 1: post-add-libs classloader refresh ----
;;
;; After `clojure.repl.deps/add-libs` mutates the runtime's shared
;; `DynamicClassLoader` (adding the freshly resolved jars to its URL
;; list), a `require` of an AOT-transitive lib against the SAME mutated
;; loader fails with a `CompilerException` (nippy -> encore). The fix
;; wraps the mutated loader in a FRESH `DynamicClassLoader` so AOT
;; class resolution proceeds against a clean loader state. These are
;; the fast, network-free unit tests for the refresh helper itself; the
;; full regression (add-lib of taoensso/nippy with :require -> loaded?
;; true) is the `^:e2e` test in tools_test.clj.

(deftest refresh-classloader-wraps-in-fresh-dynamic-loader
  (testing "refresh-classloader returns a DISTINCT DynamicClassLoader whose
            parent is the mutated loader (so the new jars stay visible)"
    (let [cl  (jvm/new-classloader)
          cl' (jvm/refresh-classloader cl)]
      (is (instance? DynamicClassLoader cl'))
      (is (not (identical? cl cl'))
          "refresh must produce a NEW loader instance")
      (is (identical? cl (.getParent ^DynamicClassLoader cl'))
          "the fresh loader's parent is the mutated one, so its URLs stay visible"))))

(deftest refresh-classloader-preserves-url-visibility
  (testing "a URL added to the original loader is still present on the
            refreshed loader's PARENT (so the new jars stay visible to the
            child via classloader delegation)"
    (let [cl   (jvm/new-classloader)
          ;; Add a dummy URL to the original loader, mirroring what
          ;; add-libs does, then refresh and assert the child's parent
          ;; still carries the URL (the child delegates class/resource
          ;; lookups to its parent, so the URL stays reachable).
          url  (.toURL (java.io.File. "."))
          _    (.addURL ^DynamicClassLoader cl url)
          cl'  (jvm/refresh-classloader cl)
          parent-urls (vec (.getURLs ^DynamicClassLoader
                                    (.getParent ^DynamicClassLoader cl')))]
      (is (instance? DynamicClassLoader cl'))
      (is (some #(= url %) parent-urls)
          "the URL added to the original loader survives on the child's parent"))))

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

(deftest eval-code-signals-reader-eval-disabled
  (testing "audit 2026-07 rec #8: the result envelope carries
            :reader-eval-disabled? true so the model knows #= is off
            and will not execute at read time"
    (let [res (jvm/eval-code 'kschltz.runtime-test.readereval
                             "(+ 1 2)" {} nil)]
      (is (m/validate schemas/EvalResult res))
      (is (true? (:reader-eval-disabled? res))))))
