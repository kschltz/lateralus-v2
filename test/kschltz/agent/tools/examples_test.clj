(ns kschltz.agent.tools.examples-test
  "Tests for the example Tool implementations."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.examples :as tools.examples]))

(deftest time-now-returns-iso-string
  (testing "time/now Tool returns an ISO-8601-ish string"
    (let [tool (tools.examples/time-now)
          result (tool/invoke-tool tool {})]
      (is (string? result))
      (is (re-find #"\d{4}-\d{2}-\d{2}T" result)))))

(deftest calculator-eval-computes-prefix-expressions
  (testing "calculator/eval Tool evaluates prefix arithmetic"
    (let [tool (tools.examples/calculator-eval)]
      (is (= "6" (tool/invoke-tool tool {:expression "(+ 1 2 3)"})))
      (is (= "20" (tool/invoke-tool tool {:expression "(* 4 5)"})))
      (is (= "5" (tool/invoke-tool tool {:expression "(/ 10 2)"}))))))

(deftest calculator-eval-rejects-unsupported-ops
  (testing "calculator/eval rejects unsupported operators and bad args"
    (let [tool (tools.examples/calculator-eval)]
      (is (string? (tool/invoke-tool tool {:expression "(pow 2 3)"})))
      (is (string? (tool/invoke-tool tool {:expression "(+ 1 \"two\")"}))))))

(deftest example-registry-contains-both-tools
  (testing "example-registry returns the two example tools"
    (let [registry (tools.examples/example-registry)]
      (is (= 2 (count registry)))
      (is (contains? registry "time/now"))
      (is (contains? registry "calculator/eval"))
      (is (every? tool/tool? (vals registry))))))
