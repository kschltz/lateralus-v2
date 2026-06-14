(ns kg-bm25-live-ollama-demo
  "Run a real LLM-backed lateralus-v2 session and print what KG + BM25 recalls.

   This demo uses a local Ollama instance for chat completions.
   It switches the memory backend to the embedding-free KG + BM25
   backend so the whole run works without ONNX or incubator Vector
   modules. The assistant is a real model, but you can still inspect
   the recall block injected into every LLM request.

   Override the model with LATERALUS_OLLAMA_MODEL. Defaults to the
   first available completion model from /api/tags.

   Usage:
     cd lateralus-v2
     LATERALUS_OLLAMA_MODEL=deepseek-v4-flash:cloud clojure -M:dev -m kg-bm25-live-ollama-demo

   The demo writes its session to ./sessions/kg-bm25-ollama-demo/.
"
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hato.client :as http]
            [integrant.core :as ig]
            [kschltz.agent.llm.schemas :as schemas]
            [kschltz.agent.memory.protocol :as mem]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.system :as system]))

(def session-id "kg-bm25-ollama-demo")
(def session-root "sessions/kg-bm25-ollama-demo")

(defn- first-completion-model
  "Return the first Ollama model from /api/tags that supports completion."
  []
  (let [resp (http/get "http://localhost:11434/api/tags"
                       {:throw-exceptions? false})
        body (json/parse-string (:body resp) true)]
    (->> (:models body)
         (filter #(some (fn [c] (= "completion" c)) (:capabilities %)))
         first
         :name)))

(defn- clean-session!
  []
  (let [dir (io/file session-root session-id)]
    (when (.exists dir)
      (doseq [f (reverse (file-seq dir))]
        (.delete f)))))

(defn- fmt-msg
  [m]
  (format "  [%s] %s" (:role m)
          (if (> (count (:content m)) 160)
            (str (subs (:content m) 0 160) "...")
            (:content m))))

(defn- print-exchange
  [n out]
  (println (str "\n=== Turn " n " ==="))
  (println "User:    " (:exchange/user-text out))
  (println "Assistant:" (schemas/extract-text out))
  (when-let [recall (:memory/recall out)]
    (println "\nRecalled messages injected into this LLM call:")
    (if (seq recall)
      (doseq [m recall]
        (println (fmt-msg m)))
      (println "  (nothing recalled yet)")))
  (println "\nFull LLM request (system + recall + current turn):")
  (doseq [m (-> out :llm/request :messages)]
    (println (fmt-msg m))))

(defn -main
  "Run the live Ollama demo."
  [& _]
  (clean-session!)
  (let [model  (or (System/getenv "LATERALUS_OLLAMA_MODEL")
                   (first-completion-model))
        _      (println "Using Ollama model:" model)
        config (-> system/default-config
                   ;; Real Ollama chat backend.
                   (assoc-in [:lateralus/llm-client]
                             {:impl :http
                              :base-url "http://localhost:11434/v1"
                              :model model})
                   (assoc-in [:lateralus/llm-config]
                             {:base-url "http://localhost:11434/v1"
                              :model model})
                   ;; No embeddings; the KG + BM25 backend is enough.
                   (assoc-in [:lateralus/embedder] {:method :noop})
                   (assoc-in [:lateralus/memory-backend]
                             {:impl :kg-bm25
                              :store {:backend :file :path session-root}
                              :top-y 3
                              :last-n 3}))
        sys    (ig/init config)
        agent  (:lateralus/agent sys)
        backend (:lateralus/memory-backend sys)
        rt     (runtime/start agent session-id)]
    (try
      (println (str "KG + BM25 live Ollama demo\n"
                    "Session:  " session-id "\n"
                    "Storage:  " session-root "/" session-id "\n"))

      (doseq [[i prompt] (map-indexed vector
                                     ["My name is Alice and my favorite color is blue. Keep your answers very short."
                                      "What is my name?"
                                      "I enjoy hiking on weekends."
                                      "What do I like to do on weekends?"
                                      "My dog's name is Charlie."
                                      "What is my dog's name and what is my favorite color?"
                                      "Actually, I now prefer green instead of blue."
                                      "Summarize everything you remember about me."])]
        (print-exchange (inc i) (runtime/send-message rt prompt)))

      (println "\n=== Final persisted transcript (all messages) ===")
      (doseq [m (mem/-recall-hybrid backend session-id {:top-y 100 :last-n 100})]
        (println (format "  %s: %s" (:role m) (:content m))))

      (finally
        (runtime/stop rt)
        (ig/halt! sys)))))
