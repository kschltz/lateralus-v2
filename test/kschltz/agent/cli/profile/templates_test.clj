(ns kschltz.agent.cli.profile.templates-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.cli.profile.templates :as t]))

(deftest build-local-has-http-client
  (let [edn (t/build {:backend :ollama-local})]
    (is (= :http (get-in edn [:lateralus/llm-client :impl])))
    (is (= t/local-base-url (get-in edn [:lateralus/llm-client :base-url])))
    (is (false? (contains? (:lateralus/llm-client edn) :api-key)))
    (is (nil? (:lateralus/workbench edn)))))

(deftest build-workbench-includes-keys
  (let [edn (t/build {:backend :ollama-local :workbench? true})]
    (is (some? (:lateralus/workbench edn)))
    (is (some? (:lateralus/workbench-plugin edn)))
    (is (contains? edn :lateralus/workflow-tools))
    (is (some #(= :lateralus/workflow-tools (:key %))
              (:lateralus/tool-registry edn)))
    (is (some? (:lateralus/stream-bus edn)))
    (is (some? (:lateralus/stream-plugin edn)))
    (is (some? (get-in edn [:lateralus/workbench :stream-bus])))))

(deftest build-cloud-url
  (is (= t/cloud-base-url
         (get-in (t/build {:backend :ollama-cloud})
                 [:lateralus/llm-client :base-url]))))

(deftest normalize-strips-api-key
  (is (nil? (:api-key (t/normalize-settings {:api-key "secret"
                                             :backend :ollama-local})))))

(deftest normalize-defaults-tool-groups
  (let [s (t/normalize-settings {:backend :ollama-local})]
    (is (true? (get-in s [:tool-groups :files])))
    (is (false? (get-in s [:tool-groups :workbench]))))
  (let [s (t/normalize-settings {:backend :ollama-local :workbench? true})]
    (is (true? (get-in s [:tool-groups :workbench])))))

(deftest store-overlay-defaults
  (testing "unset / memory keep built-in defaults"
    (is (= {} (t/store-overlay nil)))
    (is (= {} (t/store-overlay "")))
    (is (= {} (t/store-overlay "memory")))))

(deftest store-overlay-duckdb
  (testing "wires one DuckDB store into sessions, stream, file index"
    (let [ov (t/store-overlay "duckdb")]
      (is (= :duckdb (get-in ov [:lateralus/store :impl])))
      (is (string? (get-in ov [:lateralus/store :path])))
      (is (= :store (get-in ov [:lateralus/stream-bus :impl])))
      (is (some? (get-in ov [:lateralus/session-store :store])))
      (is (some? (get-in ov [:lateralus/file-index :store])))
      (is (some? (get-in ov [:lateralus/file-tools :file-index])))))
  (testing "selection is case-insensitive"
    (is (= :duckdb (get-in (t/store-overlay "DuckDB") [:lateralus/store :impl]))))
  (testing "unknown values throw a clear error"
    (is (thrown-with-msg? Exception #"Unsupported LATERALUS_STORE"
                          (t/store-overlay "redis")))))
