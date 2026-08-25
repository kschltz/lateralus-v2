(ns kschltz.agent.memory.langchain4j-embedding
  "In-process ONNX embedding via LangChain4j. JVM-only, not native-image compatible."
  (:require [kschltz.agent.memory.embedding :as embedding]
            [malli.core :as m]
            [malli.instrument :as mi])
  (:import [dev.langchain4j.model.embedding.onnx.allminilml6v2 AllMiniLmL6V2EmbeddingModel]))

(defn- embed*
  [model text]
  (let [result (.content (.embed model text))]
    (vec (.vector result))))

(defn- dimensions*
  [model]
  (.dimension (.content (.embed model "dimension probe"))))

(defn langchain4j-embedder
  "Return an Embedder backed by LangChain4j local all-MiniLM-L6-v2 ONNX model. Runs entirely in-process."
  []
  (let [model (AllMiniLmL6V2EmbeddingModel.)
        dims (dimensions* model)]
    (reify embedding/Embedder
      (-embed [_ text]
        (embed* model (or text "")))
      (-dimensions [_] dims))))

(m/=> embed* [:=> [:cat :any :string] [:vector number?]])
(m/=> dimensions* [:=> [:cat :any] [:int {:min 1}]])
(m/=> langchain4j-embedder
      [:=> [:cat] [:fn #(satisfies? embedding/Embedder %)]])

(mi/instrument!
 {:filters [(mi/-filter-ns
             'kschltz.agent.memory.langchain4j-embedding)]})
