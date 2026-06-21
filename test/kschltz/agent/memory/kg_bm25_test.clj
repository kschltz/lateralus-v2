(ns kschltz.agent.memory.kg-bm25-test
  "Tests for the file-backed KG + BM25 MemoryBackend implementation.

   These tests exercise store, recent recall, BM25/KG hybrid recall,
   session isolation, and close. They default to an in-memory store
   to avoid test flakiness from disk state, but also verify that file
   persistence writes and reloads a session."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.memory.kg-bm25 :as kg]
            [kschltz.agent.memory.protocol :as mem])
  (:import [java.io File]))

(deftest kg-bm25-satisfies-memory-backend
  (is (satisfies? mem/MemoryBackend (kg/backend {:store {:backend :memory}}))))

(defn- make-backend
  "Create an in-memory KG + BM25 backend for isolated tests."
  ([] (make-backend {}))
  ([extra]
   (kg/backend (merge {:store {:backend :memory}} extra))))

(deftest kg-bm25-stores-and-recalls-recent
  (testing "recent recall returns stored messages in chronological order"
    (let [b (make-backend)]
      (mem/-store-message b "s1" {:role "user" :content "hello"
                                    :msg-id "u1" :timestamp 100})
      (mem/-store-message b "s1" {:role "assistant" :content "hi there"
                                    :msg-id "a1" :timestamp 200})
      (is (= [{:role "user" :content "hello" :msg-id "u1" :timestamp 100}
              {:role "assistant" :content "hi there" :msg-id "a1" :timestamp 200}]
             (mem/-recall-hybrid b "s1" {:top-y 0 :last-n 5}))))))

(deftest kg-bm25-session-isolation
  (testing "recent recall only returns messages for the requested session"
    (let [b (make-backend)]
      (mem/-store-message b "s1" {:role "user" :content "one"
                                    :msg-id "u1" :timestamp 100})
      (mem/-store-message b "s2" {:role "user" :content "two"
                                    :msg-id "u2" :timestamp 200})
      (is (= [{:role "user" :content "one" :msg-id "u1" :timestamp 100}]
             (mem/-recall-hybrid b "s1" {:top-y 0 :last-n 5}))))))

(deftest kg-bm25-bm25-recall
  (testing "BM25 recall finds messages whose content matches the query"
    (let [b (make-backend)]
      (mem/-store-message b "s1" {:role "user" :content "apple pie recipe"
                                    :msg-id "u1" :timestamp 100})
      (mem/-store-message b "s1" {:role "user" :content "banana bread tips"
                                    :msg-id "u2" :timestamp 200})
      (let [recalled (mem/-recall-hybrid b "s1" {:top-y 1 :last-n 0
                                                   :query-text "apple pie recipe"})]
        (is (= 1 (count recalled)))
        (is (= "u1" (:msg-id (first recalled))))
        (is (= "apple pie recipe" (:content (first recalled)))))
      (let [recalled (mem/-recall-hybrid b "s1" {:top-y 1 :last-n 0
                                                   :query-text "banana bread"})]
        (is (= 1 (count recalled)))
        (is (= "u2" (:msg-id (first recalled))))
        (is (= "banana bread tips" (:content (first recalled)))))

(deftest kg-bm25-kg-recall
  (testing "KG recall uses entity overlap, even when wording differs"
    (let [b (make-backend)]
      (mem/-store-message b "s1" {:role "user" :content "user prefers dark mode"
                                    :msg-id "u1" :timestamp 100})
      (mem/-store-message b "s1" {:role "assistant" :content "confirmed"
                                    :msg-id "a1" :timestamp 200})
      ;; "dark" and "mode" overlap with the stored entities via default tokenizer.
      (let [recalled (mem/-recall-hybrid b "s1" {:top-y 1 :last-n 0
                                                   :query-text "dark mode"})]
        (is (= 1 (count recalled)))
        (is (= "u1" (:msg-id (first recalled)))))

(deftest kg-bm25-hybrid-dedupes
  (testing "hybrid recall merges recent and semantic results without duplicates"
    (let [b (make-backend)]
      (mem/-store-message b "s1" {:role "user" :content "apple pie recipe"
                                    :msg-id "u1" :timestamp 100})
      (mem/-store-message b "s1" {:role "assistant" :content "enjoy"
                                    :msg-id "a1" :timestamp 200})
      (let [recalled (mem/-recall-hybrid b "s1" {:top-y 2 :last-n 2
                                                   :query-text "apple pie recipe"})]
        (is (= 2 (count recalled)))
        (is (= #{"u1" "a1"} (set (map :msg-id recalled))))
        (is (= ["u1" "a1"] (map :msg-id recalled)))))

(deftest kg-bm25-empty-recall
  (testing "recall on an empty backend returns []"
    (let [b (make-backend)]
      (is (= [] (mem/-recall-hybrid b "s1" {:top-y 3 :last-n 5}))))))

(deftest kg-bm25-respects-last-n
  (testing "last-n truncates recent recall"
    (let [b (make-backend)]
      (doseq [i (range 5)]
        (mem/-store-message b "s1" {:role "user" :content (str "msg" i)
                                      :msg-id (str "m" i) :timestamp i}))
      (is (= 2 (count (mem/-recall-hybrid b "s1" {:top-y 0 :last-n 2}))))
      (is (= ["m3" "m4"] (map :msg-id (mem/-recall-hybrid b "s1" {:top-y 0 :last-n 2})))))

(deftest kg-bm25-file-persistence
  (testing "file-backed backend persists across instances"
    (let [root (doto (io/file (System/getProperty "java.io.tmpdir")
                              (str "kg-bm25-test-" (System/nanoTime)))
                 (.mkdirs))
          b1 (kg/backend {:store {:backend :file :path (.getPath root)}
                          :top-y 5 :last-n 5})]
      (try
        (mem/-store-message b1 "s1" {:role "user" :content "hello world"
                                      :msg-id "u1" :timestamp 100})
        (mem/-store-message b1 "s1" {:role "assistant" :content "hi there"
                                      :msg-id "a1" :timestamp 200})
        (mem/-close b1)
        (let [b2 (kg/backend {:store {:backend :file :path (.getPath root)}
                              :top-y 5 :last-n 5})]
          (is (= [{:role "user" :content "hello world" :msg-id "u1" :timestamp 100}
                  {:role "assistant" :content "hi there" :msg-id "a1" :timestamp 200}]
                 (mem/-recall-hybrid b2 "s1" {:top-y 0 :last-n 5})))
          (let [recalled (mem/-recall-hybrid b2 "s1" {:top-y 1 :last-n 0
                                                       :query-text "hello world"})]
            (is (= 1 (count recalled)))
            (is (= "u1" (:msg-id (first recalled)))))
          (mem/-close b2))
        (finally
          ;; Best-effort cleanup.
          (run! io/delete-file (reverse (file-seq root)))))

(deftest kg-bm25-close-is-safe
  (testing "closing the backend releases state and is idempotent"
    (let [b (make-backend)]
      (mem/-store-message b "s1" {:role "user" :content "x"
                                    :msg-id "u1" :timestamp 1})
      (is (nil? (mem/-close b)))
      (is (= [] (mem/-recall-hybrid b "s1" {:top-y 3 :last-n 5}))
          "in-memory state is cleared on close")
      (is (nil? (mem/-close b)) "double close is safe")))))
))))))))))))

