(ns kschltz.agent.system-test
  "Tests for the Integrant system definition.

   Covers:
     - default config init/halt round-trip
     - :lateralus/agent returns the expected map shape
     - empty plugin list produces empty assembled chain
     - halt policy: only keys with halt-key! are halted"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [kschltz.agent.system :as system]))

;; ---- Fixtures ----

(def ^:private system-atom (atom nil))

(use-fixtures :each
  (fn [f]
    ;; Try to halt any previous system before each test
    (when @system-atom
      (try (ig/halt! @system-atom) (catch Throwable _)))
    (reset! system-atom nil)
    (f)
    (when-let [s @system-atom]
      (try (ig/halt! s) (catch Throwable _)))))

(defn- with-system [config]
  (let [s (ig/init config)]
    (reset! system-atom s)
    s))

;; ---- Tests ----

(deftest init-default-config
  (testing "default Integrant config initializes every component"
    (let [s (with-system system/default-config)]
      (is (some? (:lateralus/llm-client s)))
      (is (some? (:lateralus/embedder s)))
      (is (some? (:lateralus/memory-backend s)))
      (is (vector? (:lateralus/plugins s)))
      (is (some? (:lateralus/agent s))))))

(deftest agent-component-has-expected-shape
  (testing ":lateralus/agent returns the canonical map"
    (let [s (with-system system/default-config)
          agent (:lateralus/agent s)]
      (is (contains? agent :agent/llm-client))
      (is (contains? agent :embedder))
      (is (contains? agent :memory-backend))
      (is (contains? agent :assembled))
      (is (vector? (:assembled agent))
          "assembled chain is a vector of interceptors")
      (is (vector? (:exchange-chain agent))))))

(deftest empty-plugins-produce-empty-assembled-chain
  (testing "MVP default: no plugins, empty assembled chain"
    (let [s (with-system system/default-config)
          agent (:lateralus/agent s)]
      (is (= [] (:assembled agent))))))

(deftest halt-closes-memory-backend
  (testing "halt! runs without throwing on the noop backend"
    (let [s (with-system system/default-config)]
      (ig/halt! s)
      (is true "halt succeeded"))))

(deftest halt-skips-keys-without-halt-key-real
  (testing "Integrant silently skips a key that has init-key but no halt-key!"
    (let [probe (atom :unharmed)
          probe-key :lateralus/probe-test-3
          config (assoc system/default-config probe-key {})]
      (defmethod ig/init-key probe-key [_ _]
        (do (reset! probe :init-ran) :value))
      ;; Deliberately NO halt-key! for this key.
      (try
        (let [s (ig/init config)]
          (is (= :init-ran @probe) "init ran")
          (ig/halt! s)
          (is (= :init-ran @probe)
              "halt did not call anything for this key (no halt-key! defined)")
          ;; The key remains in the system map untouched.
          (is (some? (probe-key s))))
        (finally
          (remove-method ig/init-key probe-key))))))
