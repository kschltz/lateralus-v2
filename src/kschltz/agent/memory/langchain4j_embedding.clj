(ns kschltz.agent.memory.langchain4j-embedding
  "In-process ONNX embedding via LangChain4j. JVM-only, not native-image compatible."
  (:require [kschltz.agent.memory.embedding :as embedding])
  (:import [dev.langchain4j.model.embedding.onnx.allminilml6v2 AllMiniLmL6V2EmbeddingModel]))

(defn langchain4j-embedder
  "Return an Embedder backed by LangChain4j local all-MiniLM-L6-v2 ONNX model. Runs entirely in-process."
  []
  (let [model (AllMiniLmL6V2EmbeddingModel.)
        dims (.dimension (.content (.embed model "dimension probe")))]
    (reify embedding/Embedder
      (-embed [_ text]
        (let [embedding (.content (.embed model (or text "")))]
          (vec (.vector embedding))))
      (-dimensions [_] dims))))
