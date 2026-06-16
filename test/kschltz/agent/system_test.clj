(ns kschltz.agent.system-test
  "Tests for the Integrant system definition.

   Covers:
     - default config init/halt round-trip
     - :lateralus/agent returns the expected map shape
     - empty plugin list produces empty assembled chain
     - halt policy: only keys with halt-key! are halted"
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [kschltz.agent.system :as system]
            [kschltz.agent.plugins.memory :as plugins.memory]
            [kschltz.agent.memory.embedding :as embedding]
            [kschltz.agent.memory.protocol :as mem]))

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
      (is (vector? (:lateralus/memory-plugin s)))
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
      (is (some #(= ::plugins.memory/recall (:name %)) (:assembled agent))
          "memory recall is in the assembled chain")
      (is (some #(= ::plugins.memory/persist (:name %)) (:assembled agent))
          "memory persist is in the assembled chain"))))

(deftest complete-plugin-replaces-base-chain
  (testing "a plugin marked :plugin/complete? true is not prepended with base"
    (let [custom-chain [{:name ::custom :enter identity}]
          config (assoc system/default-config
                        :lateralus/plugins [(with-meta custom-chain
                                              {:plugin/name :custom
                                               :plugin/complete? true})]
                        :lateralus/agent {:plugins        (ig/ref :lateralus/plugins)
                                          :llm-client     (ig/ref :lateralus/llm-client)
                                          :llm-config     (ig/ref :lateralus/llm-config)
                                          :embedder       (ig/ref :lateralus/embedder)
                                          :memory-backend (ig/ref :lateralus/memory-backend)})
          s (with-system config)
          agent (:lateralus/agent s)]
      (is (= (mapv #(select-keys % [:name :enter :leave :error]) custom-chain)
             (mapv #(select-keys % [:name :enter :leave :error]) (:exchange-chain agent)))
          "agent uses the custom chain without the base plugin")
      (is (= (mapv #(select-keys % [:name :enter :leave :error]) custom-chain)
             (mapv #(select-keys % [:name :enter :leave :error]) (:assembled agent)))))))

(deftest empty-user-plugins-produce-base-chain-only
  (testing "explicitly empty user plugins still gets the prepended base chain"
    (let [s (with-system (assoc system/default-config
                                :lateralus/plugins []
                                :lateralus/agent {:plugins        (ig/ref :lateralus/plugins)
                                                  :llm-client     (ig/ref :lateralus/llm-client)
                                                  :llm-config     (ig/ref :lateralus/llm-config)
                                                  :embedder       (ig/ref :lateralus/embedder)
                                                  :memory-backend (ig/ref :lateralus/memory-backend)}))
          agent (:lateralus/agent s)]
      (is (pos? (count (:assembled agent)))
          "base plugin interceptors are still present")
      (is (not (some #(= :memory (-> % meta :plugin/name)) (:assembled agent)))
          "memory plugin interceptors are absent when not listed"))))

(deftest base-plugin-is-first-in-default-plugins
  (testing "the default plugins vector begins with the base plugin"
    (let [s (with-system system/default-config)
          plugins (:lateralus/plugins s)]
      (is (= :base (-> plugins first meta :plugin/name)))
      (is (= :memory (-> plugins second meta :plugin/name))))))

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

(deftest system-does-not-use-requiring-resolve
  (testing "system.clj resolves optional JVM-only namespaces via normal require, not requiring-resolve"
    (let [source (slurp (io/resource "kschltz/agent/system.clj"))]
      (is (not (str/includes? source "requiring-resolve"))
          "system.clj should not use requiring-resolve for optional namespaces"))))

(deftest full-profile-integrant-initializes-optional-components
  (testing "default/full classpath can initialize :langchain4j embedder and :proximum backend"
    (let [config (-> system/default-config
                     (assoc-in [:lateralus/embedder :method] :langchain4j)
                     (assoc-in [:lateralus/memory-backend :impl] :proximum))
          s (ig/init config)]
      (try
        (is (satisfies? embedding/Embedder (:lateralus/embedder s)))
        (is (satisfies? mem/MemoryBackend (:lateralus/memory-backend s)))
        (finally
          (ig/halt! s))))))

(deftest native-profile-can-load-system
  (testing "native profile classpath can load kschltz.agent.system without optional JVM deps"
    (let [result (sh/sh "clojure" "-M:native" "-e"
                        "(require 'kschltz.agent.system) (prn :system-loaded)"
                        :dir (System/getProperty "user.dir"))]
      (is (= 0 (:exit result)) (str "native load failed: " (:err result)))
      (is (str/includes? (:out result) ":system-loaded")))))

(defn- init-throws?
  "Init `config` and return the thrown exception's diagnostic ex-data if
   it fails. Integrant wraps assert-key failures in an ExceptionInfo with
   `:reason :integrant.core/build-failed-spec`; the original Malli
   problems live in the cause's ex-data. Returns nil when no exception
   is thrown."
  [config]
  (try
    (ig/init config)
    nil
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (if (= :integrant.core/build-failed-spec (:reason data))
          (ex-data (ex-cause e))
          data)))
    (catch Throwable t
      (ex-data (ex-info (.getMessage t) {} t)))))

(deftest invalid-llm-client-fails-fast
  (testing "http client without :base-url and :model is rejected before any resources are allocated"
    (let [data (init-throws? {:lateralus/llm-client {:impl :http}})]
      (is (some? data) "init throws")
      (is (= :lateralus/llm-client (:key data)) "error names the failing key")
      (is (seq (:problems data)) "ex-data contains Malli problems")
      (is (some #(= :base-url (last (:path %))) (:problems data))
          "error mentions missing :base-url")
      (is (some #(= :model (last (:path %))) (:problems data))
          "error mentions missing :model"))))

(deftest invalid-embedder-fails-fast
  (testing "http embedder without required keys is rejected before any HTTP resources are allocated"
    (let [data (init-throws? {:lateralus/embedder {:method :http}})]
      (is (some? data) "init throws")
      (is (= :lateralus/embedder (:key data)) "error names the failing key")
      (is (seq (:problems data)) "ex-data contains Malli problems")
      (is (some #(= :base-url (last (:path %))) (:problems data))
          "error mentions missing :base-url")
      (is (some #(= :dimensions (last (:path %))) (:problems data))
          "error mentions missing :dimensions"))))

(deftest invalid-memory-backend-fails-fast
  (testing "kg-bm25 backend with invalid :store shape is rejected before touching disk"
    (let [data (init-throws? {:lateralus/memory-backend {:impl :kg-bm25
                                                         :store "not-a-map"}})]
      (is (some? data) "init throws")
      (is (= :lateralus/memory-backend (:key data)) "error names the failing key")
      (is (seq (:problems data)) "ex-data contains Malli problems")
      (is (some #(= :store (last (:path %))) (:problems data))
          "error mentions the invalid :store key"))))
