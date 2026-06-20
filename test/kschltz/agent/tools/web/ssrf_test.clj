(ns kschltz.agent.tools.web.ssrf-test
  "Tests for the Phase 3 SSRF/UA/redirect guards split out of guards.clj."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tools.web.ssrf :as ssrf]
            [kschltz.agent.tools.web.guards :as g]))

(deftest validate-and-resolve-host-returns-ip-for-public-host
  (let [r (ssrf/validate-and-resolve-host "example.com" nil {})]
    (is (contains? r :ok) (str "expected :ok, got " (pr-str r)))
    (is (string? (:ok r)))))

(deftest validate-and-resolve-host-blocks-loopback
  (let [r (ssrf/validate-and-resolve-host "127.0.0.1" nil {})]
    (is (contains? r :error))
    (is (str/includes? (:error r) "loopback"))))

(deftest validate-and-resolve-host-blocks-private
  (let [r (ssrf/validate-and-resolve-host "10.0.0.1" nil {})]
    (is (contains? r :error))
    (is (str/includes? (:error r) "private"))))

(deftest validate-and-resolve-host-blocks-cgnat
  (testing "the CGNAT range 100.64.0.0/10 is blocked even though Java's
            isSiteLocalAddress does NOT flag it"
    (let [r (ssrf/validate-and-resolve-host "100.100.100.100" nil {})]
      (is (contains? r :error))
      (is (str/includes? (:error r) "non-public")))))

(deftest validate-and-resolve-host-fail-closed-on-bad-dns
  (let [r (ssrf/validate-and-resolve-host "this-host-definitely-does-not-exist.invalid" nil {})]
    (is (contains? r :error))
    (is (str/includes? (:error r) "DNS resolution failed"))))

(deftest random-user-agent-returns-a-browser-ua
  (let [ua (ssrf/random-user-agent)]
    (is (string? ua))
    (is (or (str/includes? ua "Chrome")
            (str/includes? ua "Firefox")
            (str/includes? ua "Safari")))))

(deftest random-user-agent-rotates-within-pool
  (testing "collecting many draws hits at least 2 distinct UAs (rotation works)"
    (let [seen (into #{} (repeatedly 40 ssrf/random-user-agent))]
      (is (>= (count seen) 2)))))

(deftest snippet-truncation-hint-mentions-url-param
  (let [h (ssrf/snippet-truncation-hint)]
    (is (string? h))
    (is (str/includes? (str/lower-case h) "url"))
    (is (str/includes? (str/lower-case h) "snippet"))))

(deftest safe-redirect-target-allows-public
  (let [r (ssrf/safe-redirect-target "https://example.com/x" {})]
    (is (contains? r :ok))
    (is (= "https://example.com/x" (:ok r)))))

(deftest safe-redirect-target-blocks-private-ip
  (let [r (ssrf/safe-redirect-target "http://10.0.0.5/" {})]
    (is (contains? r :error))
    (is (str/includes? (:error r) "blocked redirect target"))))

(deftest safe-redirect-target-blocks-blank
  (let [r (ssrf/safe-redirect-target "" {})]
    (is (contains? r :error))
    (is (str/includes? (:error r) "blank"))))

(deftest safe-redirect-target-blocks-file-scheme
  (let [r (ssrf/safe-redirect-target "file:///etc/passwd" {})]
    (is (contains? r :error))))

(deftest default-config-has-duplicate-query-toggle
  (is (true? (:block-duplicate-query? (g/default-guard-config)))))