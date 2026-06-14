(ns kschltz.agent.memory.embedding-test
  "Tests for the Embedder protocol boundary.

   MVP ships a noop embedder that returns a 1-d zero vector.
   A real HTTP embedder is a follow-up that satisfies the same
   protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.memory.embedding :as embedding]))

(deftest noop-embedder-returns-zero-vector
  (testing "noop-embedder returns [0.0] for any text"
    (let [e (embedding/noop-embedder)]
      (is (= [0.0] (embedding/-embed e "hello")))
      (is (= [0.0] (embedding/-embed e "")))
      (is (= [0.0] (embedding/-embed e "a much longer piece of text"))))))

(deftest noop-embedder-dimensions
  (testing "noop-embedder reports dimensionality 1"
    (let [e (embedding/noop-embedder)]
      (is (= 1 (embedding/-dimensions e))))))
