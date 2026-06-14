(ns kschltz.agent.memory-integration-test
  "Integration tests for the memory plugin wired through the
   Integrant system and runtime.

   These tests verify that:
     - the default Integrant config assembles a chain containing
       the memory plugin interceptors
     - `runtime/send-message` exercises recall and persist
     - a custom memory backend records the exchanged messages

   No real persistent store is used; a fake in-memory backend
   satisfies the MemoryBackend protocol."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [kschltz.agent.memory.embedding :as embedding]
            [kschltz.agent.memory.protocol :as mem]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.system :as system]))

(def ^:private system-atom (atom nil))

(use-fixtures :each
  (fn [f]
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

(defn- fake-embedder
  "Deterministic embedder for integration tests. Maps the first 16
   character codes of a string to a float vector, zero-padded."
  []
  (reify embedding/Embedder
    (-embed [_ text]
      (let [codes (map float (map int (or text "")))
            padded (take 16 (concat codes (repeat 0.0)))]
        (vec padded)))
    (-dimensions [_] 16)))

(defn- recording-backend
  "Fake backend that records store calls and returns canned recall."
  [store-atom recall-data]
  (reify mem/MemoryBackend
    (-store-message [_ session-id msg]
      (swap! store-atom conj [session-id msg])
      nil)
    (-recall-hybrid [_ session-id opts]
      (swap! store-atom conj [:recall session-id opts])
      recall-data)
    (-close [_] nil)))

(deftest default-system-includes-memory-plugin
  (testing "the default config assembles a chain with memory interceptors"
    (let [s     (with-system system/default-config)
          agent (:lateralus/agent s)
          chain (:exchange-chain agent)
          names (mapv :name chain)]
      (is (some #(= :memory.enrich (:name %)) chain)
          "memory recall interceptor is present")
      (is (some #(= :memory.persist (:name %)) chain)
          "memory persist interceptor is present")
      (is (= :memory.enrich (nth names 2))
          "recall runs in the enrich slot, before compose-context"))))

(deftest runtime-uses-memory-plugin
  (testing "send-message invokes the configured memory backend"
    (let [store   (atom [])
          backend (recording-backend store [{:role "assistant" :content "recalled"}])
          config  (-> system/default-config
                      (assoc-in [:lateralus/memory-plugin :backend] backend))
          s       (with-system config)
          agent   (:lateralus/agent s)
          rt      (runtime/start agent "mem-session")
          _       (runtime/send-message rt "hello")
          _       (runtime/stop rt)]
      (is (some #(and (= :recall (first %))
                      (= "mem-session" (second %)))
                @store)
          "recall was called for the runtime session")
      (is (some #(and (= "mem-session" (first %))
                      (= "user" (get-in % [1 :role]))
                      (= "hello" (get-in % [1 :content])))
                @store)
          "user message was stored")
      (is (some #(and (= "mem-session" (first %))
                      (= "assistant" (get-in % [1 :role])))
                @store)
          "assistant message was stored"))))

(deftest proximum-backend-wired-through-runtime
  (testing "the real Proximum backend stores and recalls across exchanges"
    (let [embedder (fake-embedder)
          config   (-> system/default-config
                       (assoc-in [:lateralus/memory-backend]
                                 {:impl     :proximum
                                  :embedder embedder
                                  :dim      16
                                  :capacity 100
                                  :M        8
                                  :ef-construction 100
                                  :ef-search 100})
                       (assoc-in [:lateralus/memory-plugin :embedder] embedder)
                       (assoc-in [:lateralus/memory-plugin :top-y] 1)
                       (assoc-in [:lateralus/memory-plugin :last-n] 5))
          s       (with-system config)
          agent   (:lateralus/agent s)
          rt      (runtime/start agent "prox-session")
          _       (runtime/send-message rt "hello")
          out2    (runtime/send-message rt "hello again")
          _       (runtime/stop rt)
          msgs2   (-> out2 :llm/request :messages)]
      (is (some #(= "[recall] hello" (:content %)) msgs2)
          "the second exchange recalls the first user message")
      (is (some #(re-find #"stub LLM echoed" (:content %)) msgs2)
          "the second exchange recalls the first assistant response"))))

(deftest memory-recall-feeds-compose-context
  (testing "recalled messages appear in the composed LLM request"
    (let [store   (atom [])
          backend (recording-backend store ["previous"])
          config  (-> system/default-config
                      (assoc-in [:lateralus/memory-plugin :backend] backend))
          s       (with-system config)
          agent   (:lateralus/agent s)
          rt      (runtime/start agent "mem-session")
          out     (runtime/send-message rt "hello")
          _       (runtime/stop rt)
          msgs    (-> out :llm/request :messages)]
      (is (some #(= "[recall] previous" (:content %)) msgs)
          "recalled content is prefixed and injected into the LLM request"))))
