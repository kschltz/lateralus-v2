(ns kschltz.agent.memory.bm25-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.memory.bm25 :as bm25]))

(deftest tokenize-filters-short-and-splits
  (is (= #{"hello" "world"} (bm25/tokenize "Hello, world!")))
  (is (= #{} (bm25/tokenize "a b")))
  (is (= #{"apple" "pie" "recipe"} (bm25/tokenize "Apple pie recipe"))))

(deftest compute-idf
  (let [idfs (bm25/compute-idf 4 {"apple" 2 "pie" 1 "banana" 4})]
    (is (pos? (get idfs "apple")))
    (is (pos? (get idfs "pie")))
    (is (= 0.01 (get idfs "banana")))
    (is (nil? (get idfs "missing")))))

(deftest bm25-score-ranks-relevant-higher
  (let [idfs (bm25/compute-idf 3 {"cat" 2 "dog" 1})
        stats {"doc1" {:term-freq {"cat" 2 "dog" 0} :doc-length 3 :avg-doc-length 3}
               "doc2" {:term-freq {"cat" 0 "dog" 1} :doc-length 2 :avg-doc-length 3}}]
    (is (> (bm25/bm25-score (assoc (get stats "doc1") :idfs idfs) #{"cat"})
             (bm25/bm25-score (assoc (get stats "doc2") :idfs idfs) #{"cat"})))))

(deftest build-inverted-index
  (let [msgs [{:msg-id "m1" :content "apple pie"}
              {:msg-id "m2" :content "apple tart"}]
        idx (bm25/build-inverted-index msgs)]
    (is (= {"m1" [1] "m2" [1]} (get idx "apple")))
    (is (= {"m1" [1]} (get idx "pie")))
    (is (= {"m2" [1]} (get idx "tart")))))

(deftest corpus-stats
  (let [msgs [{:msg-id "m1" :content "apple apple pie"}
              {:msg-id "m2" :content "pie"}]
        idx (bm25/build-inverted-index msgs)
        stats (bm25/corpus-stats msgs idx)]
    (is (== 2 (get-in stats ["m1" :term-freq "apple"])))
    (is (== 3 (get-in stats ["m1" :doc-length])))
    (is (== 2 (get-in stats ["m1" :avg-doc-length])))
    (is (== 1 (get-in stats ["m2" :doc-length])))))
