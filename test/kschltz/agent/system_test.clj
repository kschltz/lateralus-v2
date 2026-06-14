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
      (is (some? (:lateralus/base-plugin s)))
      (is (some? (:lateralus/memory-plugin s)))
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
      (is (vector? (:exchange-chain agent)))
      (is (some #(= :memory.enrich (:name %)) (:assembled agent))
          "memory recall is in the assembled chain")
      (is (some #(= :memory.persist (:name %)) (:assembled agent))
          "memory persist is in the assembled chain"))))

(deftest empty-user-plugins-produce-base-chain-only
  (testing "explicitly empty user plugins still gets the prepended base chain"
    (let [s (with-system (assoc-in system/default-config
                                   [:lateralus/plugins :plugins]
                                   []))
          agent (:lateralus/agent s)]
      (is (pos? (count (:assembled agent)))
          "base plugin interceptors are still present")
      (is (not (some #(= :memory (:plugin/name %)) (:assembled agent)))
          "memory plugin interceptors are absent when not listed"))))

(deftest base-plugin-is-first-in-default-plugins
  (testing "the default plugins vector begins with the base plugin"
    (let [s (with-system system/default-config)
          plugins (:lateralus/plugins s)]
      (is (= :base (:plugin/name (first plugins))))
      (is (= :memory (:plugin/name (second plugins)))))))

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
