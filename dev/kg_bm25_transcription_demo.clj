(ns kg-bm25-transcription-demo
  "Live transcription demo of the KG + BM25 memory backend.

   This script simulates a short chat session using the stub LLM
   (the assistant simply echoes the user). The interesting part is
   the memory/recall block that the memory plugin injects into each
   LLM request:

     - top-Y messages come from BM25(query-text) fused with the
       small knowledge graph built from message tokens.
     - last-N messages come from a simple timestamp scan.

   Usage:
     cd lateralus-v2
     clojure -M:dev -m kg-bm25-transcription-demo

   The demo writes its session to ./sessions/kg-bm25-demo/ so you
   can inspect messages.edn and index.edn afterward."
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]
            [kschltz.agent.memory.protocol :as mem]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.system :as system]))

(def session-id "kg-bm25-demo")
(def session-root "sessions/kg-bm25-demo")

(defn- clean-session!
  "Remove any previous demo session files for a reproducible run."
  []
  (let [dir (io/file session-root session-id)]
    (when (.exists dir)
      (doseq [f (reverse (file-seq dir))]
        (.delete f)))))

(defn- fmt-msg
  "Pretty-print a message for the transcript, truncating long text."
  [m]
  (format "  [%s] %s" (:role m)
          (if (> (count (:content m)) 120)
            (str (subs (:content m) 0 120) "...")
            (:content m))))

(defn- print-exchange
  "Print one turn of the live transcription, including recall."
  [n out]
  (println (str "\n=== Turn " n " ==="))
  (println "User:    " (:exchange/user-text out))
  (println "Assistant:" (:exchange/response out))
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
  "Run the demo."
  [& _]
  (clean-session!)
  (let [config (-> system/default-config
                   ;; No embeddings, no ONNX, no native libs.
                   (assoc-in [:lateralus/embedder] {:method :noop})
                   ;; Switch memory to the KG + BM25 backend.
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
      (println (str "KG + BM25 live transcription demo\n"
                    "Session:  " session-id "\n"
                    "Storage:  " session-root "/" session-id "\n"))

      (doseq [[i prompt] (map-indexed vector
                                     ["My name is Alice and I like the color blue."
                                      "What is my name?"
                                      "I enjoy hiking on weekends."
                                      "What do I like to do on weekends?"
                                      "My dog's name is Charlie."
                                      "What is my dog's name and what color do I like?"
                                      "Actually, I now prefer green instead of blue."
                                      "Summarize everything you remember about me."])]
        (print-exchange (inc i) (runtime/send-message rt prompt)))

      (println "\n=== Final persisted transcript (all messages) ===")
      (doseq [m (mem/-recall-hybrid backend session-id {:top-y 100 :last-n 100})]
        (println (format "  %s: %s" (:role m) (:content m))))

      (finally
        (runtime/stop rt)
        (ig/halt! sys)))))
