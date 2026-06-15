(ns fake-llm-server
  "Tiny OpenAI-compatible fake LLM server for demos.

   Echoes the last user message unless the request contains a
   [recall] system message, in which case it says 'I remember: ...'.
   Prints its bound port on startup so callers can wire the CLI."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [ring.adapter.jetty :as jetty])
  (:import [java.io StringWriter]))

(defn- handler [req]
  (let [body (json/parse-string (slurp (:body req)) true)
        messages (:messages body)
        last-user (some #(when (= "user" (:role %)) (:content %))
                        (reverse messages))
        recall (->> messages
                    (keep #(when (= "system" (:role %)) (:content %)))
                    (filter #(str/starts-with? % "[recall] "))
                    first)
        answer (if recall
                 (str "I remember: " (subs recall (count "[recall] ")))
                 (str "No memory yet: " last-user))]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string
            {:model (:model body)
             :choices [{:message {:role "assistant" :content answer}}]})}))

(defn -main
  "Start the fake server. Writes the port number (one line) to stdout
   once the server is bound."
  [& _]
  (let [server (jetty/run-jetty handler
                                {:port 0 :host "127.0.0.1" :join? false})
        port (-> server .getURI .getPort)]
    (println port)
    ;; Block the main thread so the server stays alive.
    @(promise)))
