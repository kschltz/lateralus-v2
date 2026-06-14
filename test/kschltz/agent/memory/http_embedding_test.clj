(ns kschltz.agent.memory.http-embedding-test
  "Stub test namespace for the HTTP embedder.

   The HTTP embedder is a follow-up component; this namespace exists
   to satisfy the project quality gate that every source namespace
   has a matching test namespace. Real tests will be added when the
   embedder is wired to a fake HTTP embedding server."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.memory.http-embedding :as http-emb]
            [kschltz.agent.memory.embedding :as embedding]))

(deftest http-embedder-is-an-embedder
  (testing "the HTTP embedder constructor returns an Embedder instance"
    (let [e (http-emb/http-embedder {:base-url "http://localhost:11434/v1"
                                     :model "nomic-embed-text"
                                     :dimensions 768})]
      (is (satisfies? embedding/Embedder e))
      (is (= 768 (embedding/-dimensions e))))))
