(ns kschltz.agent.memory.proximum-backend-test
  "Tests for the Proximum MemoryBackend implementation.

   These tests exercise store, recall (recent, semantic, hybrid),
   session isolation, close, and concurrent store safety. They use
   an in-memory Proximum index and a deterministic fake embedder so
   semantic search is reproducible without network calls."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.memory.embedding :as embedding]
            [kschltz.agent.memory.protocol :as mem]
            [kschltz.agent.memory.proximum-backend :as proximum]))

(defn- fake-embedder
  "Return a deterministic embedder that maps each input string to a
   distinct float vector. The vector is the character codes of the text,
   zero-padded to `dim`. This makes exact semantic matches trivially
   retrievable by HNSW."
  [dim]
  (reify embedding/Embedder
    (-embed [_ text]
      (let [codes (map float (map int (or text "")))
            padded (take dim (concat codes (repeat 0.0)))]
        (vec padded)))
    (-dimensions [_] dim)))

(defn- make-backend
  "Create an in-memory Proximum backend with the fake embedder."
  ([] (make-backend 16))
  ([dim]
   (proximum/backend {:embedder  (fake-embedder dim)
                      :dim       dim
                      :capacity  100
                      :M         8
                      :ef-construction 100
                      :ef-search 100})))

(deftest proximum-backend-stores-and-recalls-recent
  (testing "recent recall returns stored messages in chronological order"
    (let [b (make-backend)]
      (mem/-store-message b "s1" {:role "user" :content "hello"
                                  :msg-id "u1" :timestamp 100})
      (mem/-store-message b "s1" {:role "assistant" :content "hi there"
                                  :msg-id "a1" :timestamp 200})
      (is (= [{:role "user" :content "hello" :msg-id "u1" :timestamp 100}
              {:role "assistant" :content "hi there" :msg-id "a1" :timestamp 200}]
             (mem/-recall-hybrid b "s1" {:top-y 0 :last-n 5}))))))

(deftest proximum-backend-session-isolation
  (testing "recent recall only returns messages for the requested session"
    (let [b (make-backend)]
      (mem/-store-message b "s1" {:role "user" :content "one"
                                  :msg-id "u1" :timestamp 100})
      (mem/-store-message b "s2" {:role "user" :content "two"
                                  :msg-id "u2" :timestamp 200})
      (is (= [{:role "user" :content "one" :msg-id "u1" :timestamp 100}]
             (mem/-recall-hybrid b "s1" {:top-y 0 :last-n 5}))))))

(deftest proximum-backend-semantic-recall
  (testing "semantic recall finds messages whose content matches the query"
    (let [b (make-backend)]
      (mem/-store-message b "s1" {:role "user" :content "apple pie recipe"
                                  :msg-id "u1" :timestamp 100})
      (mem/-store-message b "s1" {:role "user" :content "banana bread tips"
                                  :msg-id "u2" :timestamp 200})
      (let [recalled (mem/-recall-hybrid b "s1" {:top-y 1 :last-n 0
                                                 :query-text "apple pie recipe"})]
        (is (= 1 (count recalled)))
        (is (= "u1" (:msg-id (first recalled))))
        (is (= "apple pie recipe" (:content (first recalled))))))))

(deftest proximum-backend-hybrid-dedupes
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
        (is (= ["u1" "a1"] (map :msg-id recalled)))))))

(deftest proximum-backend-empty-recall
  (testing "recall on an empty backend returns []"
    (let [b (make-backend)]
      (is (= [] (mem/-recall-hybrid b "s1" {:top-y 3 :last-n 5}))))))

(deftest proximum-backend-close-is-safe
  (testing "closing the backend syncs and releases resources"
    (let [b (make-backend)]
      (mem/-store-message b "s1" {:role "user" :content "x"
                                  :msg-id "u1" :timestamp 1})
      (is (nil? (mem/-close b)))
      (is (nil? (mem/-close b)) "double close is safe"))))

(deftest proximum-backend-respects-last-n
  (testing "last-n truncates recent recall"
    (let [b (make-backend)]
      (doseq [i (range 5)]
        (mem/-store-message b "s1" {:role "user" :content (str "msg" i)
                                    :msg-id (str "m" i) :timestamp i}))
      (is (= 2 (count (mem/-recall-hybrid b "s1" {:top-y 0 :last-n 2}))))
      (is (= ["m3" "m4"] (map :msg-id (mem/-recall-hybrid b "s1" {:top-y 0 :last-n 2})))))))

(deftest proximum-backend-concurrent-store-completes
  (testing "many concurrent store-message calls complete without error"
    (let [b     (make-backend)
          n     32
          msgs  (mapv (fn [i]
                        {:role "user"
                         :content (str "concurrent msg " i)
                         :msg-id (str "c" i)
                         :timestamp i})
                      (range n))]
      (let [futures (doall (mapv #(future (mem/-store-message b "s1" %)) msgs))]
        (is (every? nil? (map deref futures))
            "all store-message calls return nil without throwing"))
      (let [recalled (mem/-recall-hybrid b "s1" {:top-y 0 :last-n n})]
        (is (= n (count recalled)))
        (is (= (set (map :msg-id recalled))
               (set (map :msg-id msgs))))))))
