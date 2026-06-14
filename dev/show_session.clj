(ns show-session
  "Run a live lateralus-v2 session and print what gets recalled.

   This script uses the runtime default Integrant config
   (Proximum in-memory memory + LangChain4j ONNX embedder) and the
   stub LLM. It prints each exchange with:
     - the assistant response
     - the composed LLM request messages (so you can see [recall] entries)
     - the raw recalled messages from the memory backend

   Usage:
     cd lateralus-v2
     clojure -M:dev -m show-session

   Note: first run extracts the ONNX tokenizer (~1s)."
  (:require [integrant.core :as ig]
            [kschltz.agent.cli :as cli]
            [kschltz.agent.memory.protocol :as mem]
            [kschltz.agent.runtime :as runtime]))

(defn- print-exchange
  [label out]
  (println (str "\n=== " label " ==="))
  (println "Response:" (:exchange/response out))
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
  (let [sys    (-> (cli/build-system {})
                   (assoc-in [:lateralus/memory-backend :impl] :proximum)
                   ig/init)
        agent  (:lateralus/agent sys)
        backend (:lateralus/memory-backend sys)
        rt     (runtime/start agent "long-demo")]
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
      (doseq [m (mem/-recall-hybrid backend "long-demo" {:top-y 50 :last-n 50})]
        (println (format "  %s: %s" (:role m) (:content m))))
      (finally
        (runtime/stop rt)
        (ig/halt! sys)))))
