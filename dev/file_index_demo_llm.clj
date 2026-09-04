(ns file-index-demo-llm
  "OpenAI-compatible demo LLM that drives Option D file-index tools.

   Used by `resources/lateralus/demo-file-index-workbench.edn` so the
   workbench can show real `file_reindex` / `file_write` / `file_search`
   / `file_edits` / `portal_submit` results without a live provider.

   Start:

     clojure -M:dev -m file-index-demo-llm 18765"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [ring.adapter.jetty :as jetty]))

(defn- tool-call
  [name args]
  {:id (str "call_" name)
   :type "function"
   :function {:name name
              :arguments (json/generate-string args)}})

(defn- assistant-tools
  [model calls]
  {:model model
   :choices [{:message {:role "assistant"
                        :content ""
                        :tool_calls calls}
              :finish_reason "tool_calls"}]})

(defn- assistant-text
  [model text]
  {:model model
   :choices [{:message {:role "assistant" :content text}
              :finish_reason "stop"}]})

(defn sse-body
  "Workbench HTTP client POSTs stream:true and parses SSE chunks.
   One data: line with the full message is enough — consume-sse also
   reads :message, not only :delta."
  [payload]
  (str "data: " (json/generate-string payload) "\n\n"
       "data: [DONE]\n\n"))

(defn- tool-names
  [messages]
  (->> messages
       (filter #(= "tool" (:role %)))
       (map :name)
       vec))

(defn- last-tool
  [messages]
  (last (filter #(= "tool" (:role %)) messages)))

(defn- parse-json
  [s]
  (try (json/parse-string (str s) true)
       (catch Throwable _ nil)))

(defn- edits-table
  [messages]
  (let [edits (or (:edits (parse-json (:content (last-tool messages)))) [])]
    (mapv (fn [row]
            {:tool (:tool row)
             :path (:path row)
             :sha256-after (:sha256-after row)})
          edits)))

(defn- cite-from
  [messages]
  (or (:cite (parse-json (:content (last-tool messages))))
      "@portal/file-index"))

(defn next-reply
  "Decide the next OpenAI-shaped chat completion from `messages`."
  [model messages]
  (let [seen (set (tool-names messages))]
    (cond
      (not (contains? seen "file_reindex"))
      (assistant-tools model [(tool-call "file_reindex" {:path "."})])

      (not (contains? seen "file_write"))
      (assistant-tools model [(tool-call "file_write"
                                         {:path "note.txt"
                                          :content "from workbench write\n"
                                          :create-dirs true})])

      (not (contains? seen "file_search"))
      (assistant-tools model [(tool-call "file_search"
                                         {:path "."
                                          :pattern "needle"})])

      (not (contains? seen "file_edits"))
      (assistant-tools model [(tool-call "file_edits" {:limit 10})])

      (not (contains? seen "portal_submit"))
      (assistant-tools model [(tool-call "portal_submit"
                                         {:label "file-index edits"
                                          :kind "table"
                                          :value (edits-table messages)})])

      :else
      (assistant-text
       model
       (str "File index is live. I reindexed the workspace, wrote note.txt "
            "with a SHA-256 witness, searched the index for \"needle\", and "
            "listed file_edits. Table: " (cite-from messages))))))

(defn- models-body
  []
  {:object "list"
   :data [{:id "file-index-demo" :object "model"}]})

(defn handler
  [req]
  (let [uri (str (:uri req))]
    (cond
      (and (= :get (:request-method req))
           (or (str/ends-with? uri "/models")
               (= uri "/v1/models")))
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string (models-body))}

      (and (= :post (:request-method req))
           (or (str/ends-with? uri "/chat/completions")
               (= uri "/v1/chat/completions")))
      (let [body (json/parse-string (slurp (:body req)) true)
            payload (next-reply (or (:model body) "file-index-demo")
                                (or (:messages body) []))]
        (if (:stream body)
          {:status 200
           :headers {"Content-Type" "text/event-stream; charset=utf-8"
                     "Cache-Control" "no-cache"}
           :body (sse-body payload)}
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (json/generate-string payload)}))

      :else
      {:status 404
       :headers {"Content-Type" "application/json"}
       :body "{\"error\":\"not found\"}"})))

(defn -main
  [& args]
  (let [port (or (some-> args first parse-long) 18765)
        server (jetty/run-jetty handler
                                {:port port :host "127.0.0.1" :join? false})]
    (println (str "file-index-demo-llm http://127.0.0.1:" port "/v1"))
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. #(.stop server)))
    @(promise)))
