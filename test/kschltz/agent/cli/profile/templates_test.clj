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
              (:lateralus/tool-registry edn)))))

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
