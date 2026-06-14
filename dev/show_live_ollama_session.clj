(ns show-live-ollama-session
  "Run a live lateralus-v2 session against a real Ollama LLM and
   print what gets recalled.

   Defaults to the first available model from the local Ollama
   /api/tags endpoint. Override with LATERALUS_OLLAMA_MODEL.

   Usage:
     cd lateralus-v2
     LATERALUS_OLLAMA_MODEL=deepseek-v4-flash:cloud clojure -M:dev -m show-live-ollama-session

   Note: first run extracts the ONNX tokenizer (~1s)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [hato.client :as http]
            [integrant.core :as ig]
            [kschltz.agent.cli :as cli]
            [kschltz.agent.llm.schemas :as schemas]
            [kschltz.agent.memory.protocol :as mem]
            [kschltz.agent.runtime :as runtime]))

(defn- first-model
  "Return the first model name reported by Ollama /api/tags."
  []
  (let [resp (http/get "http://localhost:11434/api/tags"
                       {:throw-exceptions? false})
        body (json/parse-string (:body resp) true)]
    (-> body :models first :name)))

(defn- print-exchange
  [label out]
  (println (str "\n=== " label " ==="))
  (println "Response:" (schemas/extract-text out))
  (println "\nLLM request messages:")
  (doseq [m (-> out :llm/request :messages)]
    (println (format "  [%s] %s" (:role m)
                     (if (> (count (:content m)) 160)
                       (str (subs (:content m) 0 160) "...")
                       (:content m)))))
  (when-let [recall (:memory/recall out)]
    (println "\nRaw recalled messages:")
    (doseq [r recall]
      (println (format "  %s" (pr-str r))))))

(defn -main
  [& _]
  (let [model  (or (System/getenv "LATERALUS_OLLAMA_MODEL") (first-model))
        _      (println "Using Ollama model:" model)
        sys    (-> (cli/build-system {})
                   (assoc-in [:lateralus/llm-client]
                             {:impl :http
                              :base-url "http://localhost:11434/v1"
                              :model model})
                   (assoc-in [:lateralus/llm-config]
                             {:base-url "http://localhost:11434/v1"
                              :model model})
                   (assoc-in [:lateralus/memory-backend :impl] :proximum)
                   ig/init)
        agent  (:lateralus/agent sys)
        backend (:lateralus/memory-backend sys)
        rt     (runtime/start agent "live-long-demo")]
    (try
      (println "Session ID:" (runtime/session-id rt))
      (print-exchange "Exchange 1: name and color"
                      (runtime/send-message rt "My name is Alice and my favorite color is blue."))
      (print-exchange "Exchange 2: ask the color"
                      (runtime/send-message rt "What is my favorite color?"))
      (print-exchange "Exchange 3: add a hobby"
                      (runtime/send-message rt "I enjoy hiking on weekends."))
      (print-exchange "Exchange 4: ask about the hobby"
                      (runtime/send-message rt "What do I like to do on weekends?"))
      (print-exchange "Exchange 5: add a pet"
                      (runtime/send-message rt "I have a dog named Charlie."))
      (print-exchange "Exchange 6: ask about the pet and color"
                      (runtime/send-message rt "What is my dog's name and what is my favorite color?"))
      (print-exchange "Exchange 7: change a fact"
                      (runtime/send-message rt "Actually, my favorite color is now green."))
      (print-exchange "Exchange 8: final summary question"
                      (runtime/send-message rt "Summarize what you know about me."))
      (println "\n--- final backend state (all recalled, chronologically) ---")
      (doseq [m (mem/-recall-hybrid backend "live-long-demo" {:top-y 50 :last-n 50})]
        (println (format "  %s: %s" (:role m) (:content m))))
      (finally
        (runtime/stop rt)
        (ig/halt! sys)))))
