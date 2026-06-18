(ns kschltz.agent.tools.web-search.guards-test
  "Tests for web search guard functions."
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.tools.web-search.guards :as guards]))

(deftest default-guard-config-exists
  (is (map? guards/default-guard-config)))

(deftest url-validation-works
  (is (= "https://example.com" (:ok (guards/validate-url "https://example.com" guards/default-guard-config)))))

(deftest query-sanitization-works
  (is (= "hello" (:ok (guards/sanitize-query "hello" guards/default-guard-config)))))

(deftest html-stripping-works
  (is (string? (guards/strip-html "<p>text</p>" 1024))))
