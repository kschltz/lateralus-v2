(ns kschltz.agent.system-test
  "Tests for the Integrant system definition.

   Covers:
     - default config init/halt round-trip
     - :lateralus/agent returns the expected map shape
     - empty plugin list produces empty assembled chain
     - Datalevin backend throws (not yet implemented)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [kschltz.agent.system :as system]
            [kschltz.agent.memory.protocol :as memory-protocol]))

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
      (is (contains? agent :llm/client))
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

(deftest datalevin-backend-not-implemented-yet
  (testing "Datalevin backend throws ex-info at init time"
    (try
      (ig/init (assoc system/default-config
                      :lateralus/memory-backend {:impl :datalevin}))
      (is false "expected throw")
      (catch clojure.lang.ExceptionInfo e
        ;; Integrant wraps the underlying ex; check the cause chain
        (let [data   (ex-data e)
              reason (:reason data)]
          (is (= :integrant.core/build-threw-exception reason))
          ;; The original ex-info from the defmethod should be on the
          ;; ex-cause chain.
          (let [causes (take-while some? (iterate #(some-> % ex-cause) e))
                matched (filter #(= "Datalevin backend not yet implemented (Step 6)"
                                      (.getMessage %))
                                 causes)]
            (is (seq matched)
                "Datalevin not-yet-implemented ex-info is on the cause chain")))))))
