(ns kschltz.agent.tools.runtime.paren-repair-test
  "Tests for the paren/delimiter rebalancing module that runs before
   clojure_eval. Covers: balanced passthrough, missing closing delimiters
   (single + nested), stray extra delimiters, nil/empty passthrough,
   unrepairable code returns the original, and that the repaired code
   actually evaluates."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tools.runtime.paren-repair :as pr]))

(deftest balanced-code-passes-through-unchanged
  (testing "already-balanced code is returned unchanged with :repaired? false"
    (let [r (pr/repair-code "(+ 1 2)")]
      (is (not (:repaired? r)))
      (is (nil? (:method r)))
      (is (= "(+ 1 2)" (:code r))))))

(deftest missing-single-close-is-repaired
  (testing "a missing closing paren is added via parinfer indent mode"
    (let [r (pr/repair-code "(+ 1 2")]
      (is (:repaired? r))
      (is (= :parinfer (:method r)))
      (is (pr/balanced? (:code r)))
      (is (= "(+ 1 2)" (:code r))))))

(deftest missing-nested-closers-are-repaired
  (testing "multiple missing closing delimiters across lines are closed"
    (let [r (pr/repair-code "(let [x 5]\n  (+ x 1")]
      (is (:repaired? r))
      (is (pr/balanced? (:code r)))
      (is (.contains (:code r) "(+ x 1))")))))

(deftest stray-extra-delimiter-is-removed
  (testing "a stray trailing delimiter is rebalanced away"
    (let [r (pr/repair-code "(+ 1 2))")]
      (is (:repaired? r))
      (is (pr/balanced? (:code r)))
      (is (= "(+ 1 2)" (:code r))))))

(deftest missing-bracket-and-brace-are-repaired
  (testing "vectors and maps with missing closers are rebalanced too"
    (let [rv (pr/repair-code "[1 2 3")
          rm (pr/repair-code "{:a 1")]
      (is (:repaired? rv))
      (is (pr/balanced? (:code rv)))
      (is (:repaired? rm))
      (is (pr/balanced? (:code rm))))))

(deftest nil-and-empty-pass-through
  (testing "nil and empty strings are returned as-is with :repaired? false"
    (let [rn (pr/repair-code nil)
          re (pr/repair-code "")]
      (is (not (:repaired? rn)))
      (is (nil? (:code rn)))
      (is (not (:repaired? re)))
      (is (= "" (:code re))))))

(deftest repaired-code-actually-evaluates
  (testing "the repaired string is readable + evaluable, not just balanced"
    (let [r (pr/repair-code "(+ 1 2")]
      (is (= 3 (clojure.core/eval (read-string (:code r))))))))