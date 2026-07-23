(ns kschltz.agent.tools.runtime.tools-test
  "Tests for the runtime-eval Tool deftypes and registry factory.

   The tool layer is exercised with a stub `ClojureRuntime` so the
   envelope, safety toggles, and coordinate parsing are verified without
   touching the network. A couple of tests use the real `JvmRuntime` for
   the local, deterministic eval path."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
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

(deftest add-lib-tool-auto-requires-namespace
  (testing "lib + require + alias evaluates a require form after loading"
    (let [cap (atom nil)
          reg (rt/runtime-registry {:runtime (stub cap)})]
      (tool/invoke-tool (get reg "clojure/add-lib")
                        {:lib "ring/ring-jetty-adapter" :version "1.13.0"
                         :require "ring.adapter.jetty" :alias "jetty"}
                        {})
      (is (= :eval (:op @cap)))
      (is (= "(require '[ring.adapter.jetty :as jetty])" (:code @cap))))))

(deftest add-lib-tool-echoes-coord-and-omits-loaded-without-require
  (testing "audit 2026-07 rec #2: when no :require is passed, :loaded? is
            ABSENT (not a false-positive true) and :coord echoes the resolved
            coordinate map for audit/version retries"
    (let [cap (atom nil)
          reg (rt/runtime-registry {:runtime (stub cap)})
          out (parse (tool/invoke-tool (get reg "clojure/add-lib")
                                       {:lib "org.clojure/data.json" :version "2.5.0"} {}))]
      (is (= {:org.clojure/data.json {:mvn/version "2.5.0"}} (:coord out))
          ":coord echoes the resolved coordinate map (JSON round-trip turns symbol keys into keywords)")
      (is (not (contains? out :loaded?))
          ":loaded? is absent when no :require was passed — no false positive"))))

(deftest add-lib-tool-reports-required-error
  (testing "when the auto-require fails, :loaded? is false and :required-error is set"
    (let [cap (atom nil)
          reg (rt/runtime-registry {:runtime
                                    (->StubRuntime
                                     {:ns "lateralus.repl" :forms 1 :value nil :output "" :error "FileNotFoundException"}
                                     {:added ["some/lib"] :error nil}
                                     ["clojure.string" "clojure.test"]
                                     cap)})]
      (let [out (parse (tool/invoke-tool (get reg "clojure/add-lib")
                                         {:lib "some/lib" :require "missing.ns"}
                                         {}))]
        (is (= ["some/lib"] (:added out)))
        (is (false? (:loaded? out)))
        (is (= "(require '[missing.ns])" (:required out)))
        (is (= "FileNotFoundException" (:required-error out)))))))

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
;; parse-coords + require-form (unit)
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

(deftest require-form-variants
  (testing "require-form builds a require statement from :require and :alias"
    (is (= "(require '[ring.adapter.jetty])" (#'rt/require-form {:require "ring.adapter.jetty"})))
    (is (= "(require '[ring.adapter.jetty :as jetty])" (#'rt/require-form {:require "ring.adapter.jetty" :alias "jetty"})))
    (is (nil? (#'rt/require-form {})))
    (is (nil? (#'rt/require-form {:require ""})))))

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

(deftest eval-tool-forwards-per-call-opts
  (testing "audit 2026-07 rec #8: EvalInput optional :max-output-bytes and
            :eval-timeout-ms are forwarded to the runtime opts so a Clerk
            render can request a bigger output window / longer timeout"
    (let [cap (atom nil)
          reg (rt/runtime-registry {:runtime (stub cap)})]
      (tool/invoke-tool (get reg "clojure/eval")
                        {:code "(+ 1 2)"
                         :max-output-bytes 131072
                         :eval-timeout-ms 60000}
                        {})
      (is (= 131072 (:max-output-bytes (:opts @cap))))
      (is (= 60000 (:eval-timeout-ms (:opts @cap)))))))

(deftest eval-tool-omits-opts-when-absent
  (testing "audit 2026-07 rec #8: when neither override is passed the
            runtime opts stay empty (default config applies)"
    (let [cap (atom nil)
          reg (rt/runtime-registry {:runtime (stub cap)})]
      (tool/invoke-tool (get reg "clojure/eval") {:code "(+ 1 2)"} {})
      (is (not (contains? (:opts @cap) :max-output-bytes)))
      (is (not (contains? (:opts @cap) :eval-timeout-ms))))))

;; ---- verify-round-3 FIX 1: AOT-transitive add-lib regression (network) ----
;;
;; The round-2 failure: clojure/add-lib of `com.taoensso/nippy` (whose
;; `taoensso.nippy.impl` references `taoensso.encore`) downloaded the jar
;; (+ transitives) and the `:reload` auto-require retry fired, but
;; `loaded?` stayed FALSE in every envelope with a persistent
;; `CompilerException` in `taoensso/nippy/impl.clj`. The fix
;; (`jvm/refresh-classloader`) wraps the mutated DynamicClassLoader in a
;; fresh one after `add-libs` so the auto-require resolves AOT classes
;; against a clean loader state. This test is SLOW (downloads nippy +
;; transitives from Maven) so it is `^:e2e` and excluded from the fast
;; suite; run with `clojure -M:e2e`.

(deftest ^:e2e add-lib-tool-loads-aot-transitive-lib-with-require
  (testing "verify-round-3 FIX 1: clojure/add-lib of an AOT-transitive lib
            (com.taoensso/nippy -> taoensso.encore) with :require returns
            loaded? TRUE after the post-add-libs classloader refresh — not
            merely the jar on the classpath"
    (let [reg (rt/runtime-registry {:enabled? true :network? true})
          out (parse (tool/invoke-tool (get reg "clojure/add-lib")
                     {:lib     "com.taoensso/nippy"
                      :version "3.4.2"
                      :require "taoensso.nippy"
                      :alias   "nippy"}
                     {}))]
      (is (some #(str/includes? % "taoensso/nippy") (:added out))
          (str "nippy was added to the classpath; added=" (pr-str (:added out))))
      (is (true? (:loaded? out))
          (str "the auto-require must SUCCEED after the classloader refresh; "
               "got loaded?=" (:loaded? out)
               " required-error=" (pr-str (:required-error out))
               " retried?=" (:require-retried? out)))
      (is (nil? (:required-error out))
          "no require error after the refresh (the :reload fallback should be dormant)"))))
