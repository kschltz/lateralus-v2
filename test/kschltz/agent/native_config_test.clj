(ns kschltz.agent.native-config-test
  "Tests for the native-image runtime config.

   The native binary excludes Proximum and LangChain4j, so the bundled
   `resources/lateralus/native.edn` wires the stub LLM, noop embedder,
   and KG + BM25 memory backend. These tests verify that config loads,
   initializes, and runs a one-shot exchange successfully on the JVM
   test classpath (the same code paths the native binary will use)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [kschltz.agent.cli :as cli]))

(deftest native-config-is-readable-edn
  (testing "resources/lateralus/native.edn exists and parses with ig/read-string"
    (let [f (io/file "resources/lateralus/native.edn")]
      (is (.exists f) "native config file exists")
      (let [config (ig/read-string (slurp f))]
        (is (map? config))
        (is (= :stub (get-in config [:lateralus/llm-client :impl])))
        (is (= :noop (get-in config [:lateralus/embedder :method])))
        (is (= :kg-bm25 (get-in config [:lateralus/memory-backend :impl])))))))

(deftest native-config-build-system-overrides-defaults
  (testing "--config resources/lateralus/native.edn selects stub + kg-bm25"
    (let [config (cli/build-system {:config "resources/lateralus/native.edn"})]
      (is (= :stub (get-in config [:lateralus/llm-client :impl])))
      (is (= :noop (get-in config [:lateralus/embedder :method])))
      (is (= :kg-bm25 (get-in config [:lateralus/memory-backend :impl])))
      ;; All Integrant refs in the agent map are resolved tag refs, not symbols.
      (is (every? ig/reflike?
                  (vals (select-keys (:lateralus/agent config)
                                     [:plugins :llm-client :llm-config
                                      :embedder :memory-backend])))))))

(deftest native-config-runs-one-shot
  (testing "a one-shot prompt with the native config prints the stub response"
    (let [out (java.io.StringWriter.)
          captured (atom nil)]
      (binding [*out* out]
        (cli/run-cli
         {:action :one-shot
          :config "resources/lateralus/native.edn"
          :prompt "hello native"}
         {:in (java.io.StringReader. "")
          :out out
          :exit (fn [n] (reset! captured n))}))
      (is (= 0 @captured) "one-shot exits 0")
      (is (str/includes? (str out) "lateralus-v2 stub LLM echoed: hello native")
          "stub LLM response is printed"))))
