(ns kschltz.agent.quality-test
  "Quality-gate tests (Step 10).

   These tests verify the structural invariants from the plan's
   verification matrix and done condition:
     - every src namespace has a corresponding test namespace
     - no source file exceeds 500 LOC without tests
     - chain + runtime combined stay under 350 LOC
     - no forbidden patterns (add-*-tool!, http/completion outside
       llm/http, agent.loop in src)

   The tests are intentionally mechanical: they fail the build
   when the project structure drifts."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- src-files
  "Return all .clj files under src/."
  []
  (->> (file-seq (io/file "src"))
       (filter #(str/ends-with? (.getName %) ".clj"))
       (sort)))

(defn- test-files
  "Return all .clj files under test/."
  []
  (->> (file-seq (io/file "test"))
       (filter #(str/ends-with? (.getName %) ".clj"))
       (sort)))

(defn- ns-from-file
  "Read the ns declaration from a Clojure file and return its symbol."
  [f]
  (let [form (-> f slurp read-string)]
    (when (and (seq? form) (= 'ns (first form)))
      (second form))))

(defn- test-ns-for
  "Return the expected test-ns symbol for a src-ns symbol.
   e.g. kschltz.agent.chain => kschltz.agent.chain-test"
  [src-ns]
  (symbol (str (name src-ns) "-test")))

(deftest every-src-ns-has-a-test-ns
  (testing "each source namespace has a matching test namespace"
    (let [src-nses  (keep ns-from-file (src-files))
          test-nses (set (keep ns-from-file (test-files)))
          missing   (remove #(contains? test-nses (test-ns-for %)) src-nses)]
      (is (empty? missing)
          (str "missing test namespaces for: " (pr-str missing))))))

(deftest no-src-file-exceeds-500-loc-without-tests
  (testing "no source file exceeds 500 lines; files that do must have tests"
    (doseq [f (src-files)]
      (let [lines (-> f slurp str/split-lines count)]
        (is (<= lines 500)
            (str (.getPath f) " is " lines " lines; max allowed is 500"))))))

(deftest chain-plus-runtime-under-350-loc
  (testing "combined chain + runtime stay under the plan's 350 LOC target"
    (let [chain-loc   (-> "src/kschltz/agent/chain.clj" slurp str/split-lines count)
          runtime-loc (-> "src/kschltz/agent/runtime.clj" slurp str/split-lines count)]
      (is (< (+ chain-loc runtime-loc) 350)
          (str "chain + runtime = " (+ chain-loc runtime-loc) " lines; plan target < 350")))))

(deftest no-add-tool-installer-functions
  (testing "no ad-hoc add-*-tool! installer functions exist in src"
    (let [hits (for [f (src-files)
                     :let [text (slurp f)]
                     :when (re-find #"add-.*-tool!" text)]
                 (.getPath f))]
      (is (empty? hits)
          (str "forbidden add-*-tool! pattern found in: " (pr-str hits))))))

(deftest no-direct-http-completion-outside-llm-http
  (testing "http/completion only appears in the llm/http boundary ns"
    (let [hits (for [f (src-files)
                     :let [text (slurp f)
                           rel-path (.getPath f)]
                     :when (and (not (str/includes? rel-path "llm/http"))
                                (re-find #"http/completion" text))]
                 rel-path)]
      (is (empty? hits)
          (str "http/completion outside llm/http.clj: " (pr-str hits))))))

(deftest no-loop-clj-dependency-in-interceptors
  (testing "interceptors do not depend on kschltz.agent.loop"
    (let [ix-f    (io/file "src/kschltz/agent/interceptors.clj")
          text    (slurp ix-f)
          aliases (re-seq #"\[([^\]]+) :as ([^\]]+)\]" text)
          bad     (filter (fn [[_ ns-sym _]]
                            (str/starts-with? ns-sym "kschltz.agent.loop"))
                          aliases)]
      (is (empty? bad)
          (str "interceptors.clj imports loop namespace: " (pr-str bad))))))
