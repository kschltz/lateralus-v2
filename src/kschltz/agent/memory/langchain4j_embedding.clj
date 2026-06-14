(ns kschltz.agent.memory.langchain4j-embedding
  "In-process ONNX embedding via LangChain4j.

   Uses the bundled all-MiniLM-L6-v2 model (384 dimensions). The model
   weights are packaged inside the langchain4j-embeddings jar, so no
   network calls are made at runtime.

   This is the default embedder when the runtime config selects
   `:method :langchain4j`. It is JVM-only: the ONNX runtime uses JNI,
   so it is not compatible with GraalVM native-image. For native-image,
   use an HTTP embedder instead.

   API:
     (-embed embedder text)    -> vector of 384 floats
     (-dimensions embedder)  -> 384"
  (:require [kschltz.agent.memory.embedding :as embedding])
  (:import [dev.langchain4j.model.embedding.onnx.allminilml6v2 AllMiniLmL6V2EmbeddingModel]))

(defn langchain4j-embedder
  "Return an `Embedder` backed by LangChain4j's local
   all-MiniLM-L6-v2 ONNX model. Runs entirely in-process."
  []
  (let [model (AllMiniLmL6V2EmbeddingModel.)
        dims (.dimension (.content (.embed model "dimension probe")))]
    (reify embedding/Embedder
      (-embed [_ text]
        (let [embedding (.content (.embed model (or text "")))]
          (vec (.vector embedding))))
      (-dimensions [_] dims))))
