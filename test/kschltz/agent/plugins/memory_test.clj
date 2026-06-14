(ns kschltz.agent.plugins.memory-test
  "Tests for the memory plugin.

   The plugin closes over a `MemoryBackend` and an optional
   `Embedder`. Its interceptors set `:memory/recall` on the ctx
   (`:enrich`) and call `-store-message` on the backend (`:persist`).

   These tests use in-memory fake backends and embedders; no real
   store or network call is made."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.memory.embedding :as embedding]
            [kschltz.agent.memory.protocol :as mem]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.plugins.memory :as plugins.memory]))

;; ---- Fake backend that records calls and returns canned recall ----

(defn- recording-backend
  "Return a reify of MemoryBackend that records store calls in
   `store-atom` and returns `recall-data` from `-recall-hybrid`."
  [store-atom recall-data]
  (reify mem/MemoryBackend
    (-store-message [_ session-id msg]
      (swap! store-atom conj [:store session-id msg])
      nil)
    (-recall-hybrid [_ session-id opts]
      (swap! store-atom conj [:recall session-id opts])
      recall-data)
    (-close [_] nil)))

(defn- fake-embedder
  "Return a reify of Embedder that returns a deterministic zero vector."
  [dims]
  (reify embedding/Embedder
    (-embed [_ _text]
      (vec (repeat dims 0.0)))
    (-dimensions [_] dims)))

;; ---- Unit tests for the plugin constructor ----

(deftest memory-plugin-construction
  (testing "the plugin has the expected name and slots"
    (let [b (recording-backend (atom []) [])
          p (plugins.memory/memory-plugin {:backend b})]
      (is (= :memory (:plugin/name p)))
      (is (= 1 (count (get-in p [:plugin/slots :enrich]))))
      (is (= 1 (count (get-in p [:plugin/slots :persist])))))))

(deftest memory-plugin-default-recall-opts
  (testing "defaults are top-y 3 and last-n 5"
    (let [calls (atom [])
          b     (reify mem/MemoryBackend
                  (-store-message [_ _ _] nil)
                  (-recall-hybrid [_ _ opts]
                    (swap! calls conj opts)
                    [])
                  (-close [_] nil))
          p     (plugins.memory/memory-plugin {:backend b})
          ix    (first (get-in p [:plugin/slots :enrich]))
          _     ((:enter ix) {:exchange/session-id :s
                              :exchange/user-text  "hi"})]
      (is (= [{:top-y 3 :last-n 5 :query-text "hi" :query-embedding nil}]
             @calls)))))

(deftest memory-plugin-recall-embeds-query
  (testing "when an embedder is provided, recall opts include the embedding"
    (let [calls (atom [])
          b     (reify mem/MemoryBackend
                  (-store-message [_ _ _] nil)
                  (-recall-hybrid [_ _ opts]
                    (swap! calls conj opts)
                    [])
                  (-close [_] nil))
          e     (fake-embedder 4)
          p     (plugins.memory/memory-plugin {:backend b :embedder e})
          ix    (first (get-in p [:plugin/slots :enrich]))
          _     ((:enter ix) {:exchange/session-id :s
                              :exchange/user-text  "hello"})]
      (is (= 1 (count @calls)))
      (is (= [0.0 0.0 0.0 0.0] (:query-embedding (first @calls)))))))

;; ---- Integration: memory plugin + base plugin via chain ----

(deftest memory-recall-sets-ctx-key
  (testing "recall interceptor sets :memory/recall on the ctx"
    (let [store (atom [])
          b     (recording-backend store [{:role "assistant" :content "previous"}])
          p     (plugins.memory/memory-plugin {:backend b :top-y 2 :last-n 4})
          chain (plugin/assemble-chain [(plugins.base/base-plugin) p])
          out   (chain/execute
                 {:agent/state        {:base-url "stub" :api-key nil :model "stub/v0"
                                       :agent/system-message "sys"}
                  :exchange/session-id :test-session
                  :exchange/user-msg-id "u1"
                  :exchange/assistant-msg-id "a1"
                  :exchange/user-text  "hello"}
                 chain)]
      (is (= [{:role "assistant" :content "previous"}] (:memory/recall out)))
      (is (some #(= [:recall :test-session {:top-y 2 :last-n 4
                                            :query-text "hello"
                                            :query-embedding nil}]
                    %)
                @store)))))

(deftest memory-persist-stores-messages
  (testing "persist interceptor stores user and assistant messages on leave"
    (let [store (atom [])
          b     (recording-backend store [])
          p     (plugins.memory/memory-plugin {:backend b})
          chain (plugin/assemble-chain [(plugins.base/base-plugin) p])
          out   (chain/execute
                 {:agent/state        {:base-url "stub" :api-key nil :model "stub/v0"
                                       :agent/system-message "sys"}
                  :exchange/session-id :test-session
                  :exchange/user-msg-id "u1"
                  :exchange/assistant-msg-id "a1"
                  :exchange/user-text  "hello"}
                 chain)]
      (is (some #(and (= :store (first %))
                      (= :test-session (second %))
                      (= "user" (get-in % [2 :role]))
                      (= "hello" (get-in % [2 :content])))
                @store)
          "user message was stored")
      (is (some #(and (= :store (first %))
                      (= :test-session (second %))
                      (= "assistant" (get-in % [2 :role]))
                      (string? (get-in % [2 :content])))
                @store)
          "assistant message was stored")
      (is (= 3 (count @store))
          "one recall + two store calls"))))

(deftest memory-plugin-noop-backend-is-safe
  (testing "with the noop backend the memory plugin is a no-op end-to-end"
    (let [noop-b (reify mem/MemoryBackend
                   (-store-message [_ _ _] nil)
                   (-recall-hybrid [_ _ _] [])
                   (-close [_] nil))
          p      (plugins.memory/memory-plugin {:backend noop-b})
          chain  (plugin/assemble-chain [(plugins.base/base-plugin) p])
          out    (chain/execute
                  {:agent/state        {:base-url "stub" :api-key nil :model "stub/v0"
                                        :agent/system-message "sys"}
                   :exchange/session-id :test-session
                   :exchange/user-msg-id "u1"
                   :exchange/assistant-msg-id "a1"
                   :exchange/user-text  "hello"}
                  chain)]
      (is (= [] (:memory/recall out)))
      (is (string? (:exchange/response out))))))
