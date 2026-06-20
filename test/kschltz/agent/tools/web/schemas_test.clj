(ns kschltz.agent.tools.web.schemas-test
  "Malli schema validation tests for the web tool schemas."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [kschltz.agent.tools.web.schemas :as s]))

(defn- valid?
  "True when Malli accepts `value` against `schema`."
  [schema value]
  (nil? (m/explain schema value)))

;; ---------------------------------------------------------------------------
;; Op input schemas
;; ---------------------------------------------------------------------------

(deftest web-search-input
  (testing "Accepts query + optional result-count"
    (is (valid? s/WebSearchInput {:query "ducks" :result-count 5})))
  (testing "Accepts query alone (result-count is optional)"
    (is (valid? s/WebSearchInput {:query "ducks"})))
  (testing "Rejects empty query"
    (is (not (valid? s/WebSearchInput {:query ""}))))
  (testing "Rejects missing query"
    (is (not (valid? s/WebSearchInput {:result-count 5})))))

(deftest web-fetch-input
  (testing "Accepts https URL"
    (is (valid? s/WebFetchInput {:url "https://x"})))
  (testing "Accepts http URL"
    (is (valid? s/WebFetchInput {:url "http://example.com/"})))
  (testing "Accepts optional max-bytes"
    (is (valid? s/WebFetchInput {:url "https://x" :max-bytes 4096})))
  (testing "Rejects javascript: URL as a non-empty string"
    ;; The schema is `[:map [:url :string]]` — it accepts any non-empty
    ;; string. The guard layer (validate-url) is the layer that
    ;; rejects `javascript:`. This test pins that contract.
    (is (valid? s/WebFetchInput {:url "javascript:alert(1)"})))
  (testing "Rejects missing url"
    (is (not (valid? s/WebFetchInput {})))))

(deftest web-extract-input
  (testing "Accepts html payload"
    (is (valid? s/WebExtractInput {:html "<p>x</p>"})))
  (testing "Accepts optional selector"
    (is (valid? s/WebExtractInput {:html "<p>x</p>" :selector "article"})))
  (testing "Rejects nil html"
    (is (not (valid? s/WebExtractInput {:html nil}))))
  (testing "Rejects missing html"
    (is (not (valid? s/WebExtractInput {})))))

(deftest op-output-schemas-are-string
  (testing "All op output schemas are :string"
    (is (= :string s/WebSearchOutput))
    (is (= :string s/WebFetchOutput))
    (is (= :string s/WebExtractOutput))))

;; ---------------------------------------------------------------------------
;; Provider config schema
;; ---------------------------------------------------------------------------

(deftest web-config-accepts-known-providers
  (testing "Accepts :none"
    (is (valid? s/WebConfig {:provider :none})))
  (testing "Accepts :mojeek"
    (is (valid? s/WebConfig {:provider :mojeek})))
  (testing "Accepts :searxng"
    (is (valid? s/WebConfig {:provider :searxng}))))

(deftest web-config-rejects-unknown-providers
  (testing "Rejects :tavily (not in enum)"
    (is (not (valid? s/WebConfig {:provider :tavily}))))
  (testing "Rejects missing provider"
    (is (not (valid? s/WebConfig {})))))

(deftest web-config-accepts-guard-overrides
  (testing "All guard toggles can be overridden"
    (is (valid? s/WebConfig
                {:provider :none
                 :max-query-length 200
                 :max-page-bytes 1024
                 :block-private-ips? false
                 :allowed-schemes #{"http"}
                 :allowed-ports #{80}
                 :url-allow-list ["example.com"]
                 :url-block-list ["evil.com"]}))))

;; ---------------------------------------------------------------------------
;; Guard result schemas
;; ---------------------------------------------------------------------------

(deftest safe-url-shape
  (testing "Accepts allow? true"
    (is (valid? s/SafeUrl {:allow? true  :url "https://x" :reason "ok"})))
  (testing "Accepts allow? false"
    (is (valid? s/SafeUrl {:allow? false :url "x" :reason "denied"})))
  (testing "Rejects when :allow? missing"
    (is (not (valid? s/SafeUrl {:url "x" :reason "ok"}))))
  (testing "Rejects when :reason missing"
    (is (not (valid? s/SafeUrl {:allow? true :url "x"})))))

(deftest sanitized-query-shape
  (testing "Accepts :ok branch"
    (is (valid? s/SanitizedQuery {:ok "clean query"})))
  (testing "Accepts :error branch"
    (is (valid? s/SanitizedQuery {:error "too long"})))
  (testing "Rejects an unknown key"
    (is (not (valid? s/SanitizedQuery {:foo "bar"})))))

(deftest sanitized-snippet-shape
  (testing "Accepts :ok branch"
    (is (valid? s/SanitizedSnippet {:ok "clean snippet"})))
  (testing "Accepts :error branch"
    (is (valid? s/SanitizedSnippet {:error "self-activation"}))))