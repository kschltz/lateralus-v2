(ns kschltz.agent.tools.runtime.tools-test
  "Tests for the runtime-eval Tool deftypes and registry factory.

   The tool layer is exercised with a stub `ClojureRuntime` so the
   envelope, safety toggles, and coordinate parsing are verified without
   touching the network. A couple of tests use the real `JvmRuntime` for
   the local, deterministic eval path."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.runtime.protocol :as proto]
            [kschltz.agent.tools.runtime.tools :as rt]))

;; ---------------------------------------------------------------------------
;; Stub runtime
;; ---------------------------------------------------------------------------

(deftype StubRuntime [eval-result add-result libs capture]
  proto/ClojureRuntime
  (-eval [_ code opts]
    (reset! capture {:op :eval :code code :opts opts})
    eval-result)
  (-add-libs [_ coords opts]
    (reset! capture {:op :add-libs :coords coords :opts opts})
    add-result)
  (-loaded-libs [_] libs)
  (-capabilities [_] {:eval? true :add-libs? true :network? true}))

(defn- stub
  ([] (stub (atom nil)))
  ([capture]
   (->StubRuntime {:ns "lateralus.repl" :forms 1 :value "3" :output "" :error nil}
                  {:added ["org.clojure/data.json"] :error nil}
                  ["clojure.string" "clojure.test"]
                  capture)))

(defn- parse [s] (json/parse-string s true))

;; ---------------------------------------------------------------------------
;; Registry factory
;; ---------------------------------------------------------------------------

(deftest registry-contains-three-tools
  (testing "runtime-registry returns the three runtime-eval tools"
    (let [reg (rt/runtime-registry {:runtime (stub)})]
      (is (= 3 (count reg)))
      (is (contains? reg "clojure/eval"))
      (is (contains? reg "clojure/add-lib"))
      (is (contains? reg "clojure/loaded-libs"))
      (is (every? tool/tool? (vals reg))))))

(deftest registry-tool-names-are-exact
  (testing "the (-name _) values match the registry keys"
    (let [reg (rt/runtime-registry {:runtime (stub)})]
      (is (= "clojure/eval"        (tool/-name (get reg "clojure/eval"))))
      (is (= "clojure/add-lib"     (tool/-name (get reg "clojure/add-lib"))))
      (is (= "clojure/loaded-libs" (tool/-name (get reg "clojure/loaded-libs")))))))

(deftest registry-builds-jvm-runtime-by-default
  (testing "with no injected :runtime the registry evaluates real code"
    (let [reg (rt/runtime-registry {})
          out (parse (tool/invoke-tool (get reg "clojure/eval") {:code "(+ 1 2)"} {}))]
      (is (= "3" (:value out)))
      (is (nil? (:error out))))))

;; ---------------------------------------------------------------------------
;; clojure/eval
;; ---------------------------------------------------------------------------

(deftest eval-tool-returns-envelope-from-runtime
  (testing "the eval tool serializes the runtime result and forwards :ns"
    (let [cap (atom nil)
          reg (rt/runtime-registry {:runtime (stub cap)})
          out (parse (tool/invoke-tool (get reg "clojure/eval")
                                       {:code "(+ 1 2)" :ns "my.ns"} {}))]
      (is (= "3" (:value out)))
      (is (= "(+ 1 2)" (:code @cap)))
      (is (= "my.ns" (:ns (:opts @cap)))))))

(deftest eval-tool-omits-ns-when-absent
  (testing "no :ns argument means the runtime gets an empty opts map"
    (let [cap (atom nil)
          reg (rt/runtime-registry {:runtime (stub cap)})]
      (tool/invoke-tool (get reg "clojure/eval") {:code "(+ 1 2)"} {})
      (is (not (contains? (:opts @cap) :ns))))))

(deftest eval-tool-disabled-envelope
  (testing ":enabled? false short-circuits to a disabled envelope"
    (let [reg (rt/runtime-registry {:runtime (stub) :enabled? false})
          out (parse (tool/invoke-tool (get reg "clojure/eval") {:code "(+ 1 2)"} {}))]
      (is (= "disabled" (:phase out)))
      (is (some? (:error out))))))

;; ---------------------------------------------------------------------------
;; clojure/add-lib
;; ---------------------------------------------------------------------------

(deftest add-lib-tool-builds-mvn-coords-from-lib-and-version
  (testing "lib + version becomes {lib {:mvn/version version}}"
    (let [cap (atom nil)
          reg (rt/runtime-registry {:runtime (stub cap)})
          out (parse (tool/invoke-tool (get reg "clojure/add-lib")
                                       {:lib "org.clojure/data.json" :version "2.5.0"} {}))]
      (is (= ["org.clojure/data.json"] (:added out)))
      (is (= '{org.clojure/data.json {:mvn/version "2.5.0"}} (:coords @cap))))))

(deftest add-lib-tool-defaults-version-to-release
  (testing "lib without version defaults to the RELEASE coordinate"
    (let [cap (atom nil)
          reg (rt/runtime-registry {:runtime (stub cap)})]
      (tool/invoke-tool (get reg "clojure/add-lib") {:lib "org.clojure/data.json"} {})
      (is (= '{org.clojure/data.json {:mvn/version "RELEASE"}} (:coords @cap))))))

(deftest add-lib-tool-parses-coords-edn
  (testing "an explicit :coords EDN map is parsed and symbol-keyed"
    (let [cap (atom nil)
          reg (rt/runtime-registry {:runtime (stub cap)})]
      (tool/invoke-tool (get reg "clojure/add-lib")
                        {:coords "{org.clojure/data.json {:mvn/version \"2.5.0\"}}"} {})
      (is (= '{org.clojure/data.json {:mvn/version "2.5.0"}} (:coords @cap))))))

(deftest add-lib-tool-network-disabled-envelope
  (testing ":network? false blocks add-lib with a network-disabled envelope"
    (let [reg (rt/runtime-registry {:runtime (stub) :network? false})
          out (parse (tool/invoke-tool (get reg "clojure/add-lib")
                                       {:lib "org.clojure/data.json"} {}))]
      (is (= "network-disabled" (:phase out)))
      (is (= [] (:added out))))))

(deftest add-lib-tool-error-envelope-on-bad-args
  (testing "missing lib and coords yields a tool error envelope"
    (let [reg (rt/runtime-registry {:runtime (stub)})
          out (parse (tool/invoke-tool (get reg "clojure/add-lib") {} {}))]
      (is (= "tool" (:phase out)))
      (is (some? (:error out))))))

;; ---------------------------------------------------------------------------
;; clojure/loaded-libs
;; ---------------------------------------------------------------------------

(deftest loaded-libs-tool-returns-libs
  (testing "the loaded-libs tool wraps the runtime's lib list"
    (let [reg (rt/runtime-registry {:runtime (stub)})
          out (parse (tool/invoke-tool (get reg "clojure/loaded-libs") {} {}))]
      (is (= ["clojure.string" "clojure.test"] (:libs out))))))

(deftest loaded-libs-tool-rejects-arguments
  (testing "the loaded-libs input schema is closed; extra args are rejected"
    (let [reg (rt/runtime-registry {:runtime (stub)})
          out (tool/invoke-tool (get reg "clojure/loaded-libs") {:extra true} {})]
      (is (string? out))
      (is (re-find #"validation" out)))))

;; ---------------------------------------------------------------------------
;; parse-coords (unit)
;; ---------------------------------------------------------------------------

(deftest parse-coords-variants
  (testing "parse-coords handles lib+version, lib-only, and coords EDN"
    (is (= '{a/b {:mvn/version "1.0"}} (rt/parse-coords {:lib "a/b" :version "1.0"})))
    (is (= '{a/b {:mvn/version "RELEASE"}} (rt/parse-coords {:lib "a/b"})))
    (is (= '{a/b {:mvn/version "2"}}
           (rt/parse-coords {:coords "{a/b {:mvn/version \"2\"}}"}))))
  (testing "parse-coords throws on empty input and non-map coords"
    (is (thrown? clojure.lang.ExceptionInfo (rt/parse-coords {})))
    (is (thrown? clojure.lang.ExceptionInfo (rt/parse-coords {:coords "[1 2 3]"})))))

;; ---- paren-repair integration (2026-06-22) ----

(deftest eval-tool-repairs-broken-code-before-evaluating
  (testing "clojure/eval repairs missing delimiters before running the code,
            and flags :paren-repaired? in the JSON envelope"
    (let [reg  (rt/runtime-registry {:enabled? true})
          etool (get reg "clojure/eval")
          out   (json/parse-string (tool/invoke-tool etool {:code "(+ 1 2"} {}) true)]
      (is (= 3 (-> out :value read-string))
          "the broken (+ 1 2 was repaired to (+ 1 2) and evaluated to 3")
      (is (true? (:paren-repaired? out))
          "the envelope must flag that a repair was applied")
      (is (= "parinfer" (:paren-repair-method out))
          "the repair method is recorded"))))

(deftest eval-tool-leaves-balanced-code-unrepaired
  (testing "balanced code is not flagged as repaired"
    (let [reg  (rt/runtime-registry {:enabled? true})
          etool (get reg "clojure/eval")
          out   (json/parse-string (tool/invoke-tool etool {:code "(+ 1 2)"} {}) true)]
      (is (= 3 (-> out :value read-string)))
      (is (nil? (:paren-repaired? out))
          "balanced code must not carry the :paren-repaired? flag"))))
