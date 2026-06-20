(ns kschltz.agent.tools.web.web-e2e-test
  "End-to-end tests for the live `:mojeek` web provider.

   These tests hit the real public internet and are therefore tagged
   with `^:e2e`. The default `clojure -M:test` skips them. Run them
   explicitly with:

       LATERALUS_E2E_WEB=true clojure -M:test -i :e2e

   or via the dedicated alias:

       LATERALUS_E2E_WEB=true clojure -M:e2e

   The tests verify that the `:mojeek` provider can parse a real Mojeek
   result page and that `web/fetch` can retrieve a public page. They
   are intentionally lenient — markup drift should not break the build,
   but it should surface in the e2e run so an operator can decide to
   update the selector map."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.tools.web.mojeek :as mojeek]
            [kschltz.agent.tools.web.protocol :as protocol]))

(def ^:private e2e-enabled?
  "True when the operator explicitly opts into live-web e2e tests."
  (boolean (System/getenv "LATERALUS_E2E_WEB")))

(use-fixtures :each
  (fn [f]
    (when e2e-enabled?
      (f))))

(deftest ^:e2e mojeek-search-returns-results
  (testing "a live Mojeek search returns at least one parseable result"
    (let [provider (mojeek/provider {})
          result   (protocol/-search provider "clojure programming language" {})]
      (is (= :mojeek (:provider result)))
      (is (vector? (:results result)))
      (is (pos? (count (:results result)))
          "Mojeek result page structure may have changed if this is zero")
      (let [r (first (:results result))]
        (is (string? (:title r)))
        (is (string? (:url r)))
        (is (string? (:snippet r)))))))

(deftest ^:e2e mojeek-fetch-returns-page-text
  (testing "web/fetch can retrieve and strip a public page"
    (let [provider (mojeek/provider {})
          result   (protocol/-fetch provider "https://example.com/" {})]
      (is (= 200 (:status result)))
      (is (string? (:body result)))
      (is (pos? (:bytes result)))
      (is (string? (:title result))))))
