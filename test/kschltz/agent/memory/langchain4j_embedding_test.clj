(ns kschltz.agent.memory.langchain4j-embedding-test
  "Tests for the LangChain4j in-process ONNX embedder.

   These tests load the bundled all-MiniLM-L6-v2 model, so they are
   slower than the noop/fake embedder tests. They verify that the
   embedder satisfies the Embedder protocol and returns sensible
   384-dimensional vectors."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.memory.embedding :as embedding]
            [kschltz.agent.memory.langchain4j-embedding :as lc4j]))

(defn- cosine-sim
  "Cosine similarity of two float vectors."
  [a b]
  (let [dot    (reduce + (map * a b))
        norm-a (Math/sqrt (reduce + (map #(* % %) a)))
        norm-b (Math/sqrt (reduce + (map #(* % %) b)))]
    (if (and (pos? norm-a) (pos? norm-b))
      (/ dot (* norm-a norm-b))
      0.0)))

(deftest langchain4j-embedder-returns-384-dimensions
  (testing "the embedder reports and returns 384-dimensional vectors"
    (let [e (lc4j/langchain4j-embedder)]
      (is (= 384 (embedding/-dimensions e)))
      (is (= 384 (count (embedding/-embed e "hello"))))
      (is (every? float? (embedding/-embed e "world"))))))

(deftest langchain4j-embedder-is-deterministic
  (testing "the same text produces the same embedding"
    (let [e (lc4j/langchain4j-embedder)
          a (embedding/-embed e "the quick brown fox")
          b (embedding/-embed e "the quick brown fox")]
      (is (= a b)))))

(deftest langchain4j-embedder-preserves-similarity
  (testing "similar texts have higher cosine similarity than unrelated texts"
    (let [e     (lc4j/langchain4j-embedder)
          v1    (embedding/-embed e "I love programming in Clojure")
          v2    (embedding/-embed e "Clojure is my favorite programming language")
          v3    (embedding/-embed e "The weather is sunny today")]
      (is (< 0.5 (cosine-sim v1 v2))
          "similar Clojure texts are correlated")
      (is (< (cosine-sim v1 v3) (cosine-sim v1 v2))
          "unrelated text is less similar than related text"))))
