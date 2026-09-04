(ns file-index-demo-llm
  "OpenAI-compatible demo LLM for the workbench store profile.

   File-index path: `file_reindex` / `file_write` / `file_search` /
   `file_edits` / `portal_submit`.

   Session-tool path (user text matches add_two / session tool /
   tool_define): `tool_define` / `tool_test` / `add_two` /
   `tool_list_runtime` / `portal_submit`.

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

(defn- last-user-text
  [messages]
  (->> messages
       (filter #(= "user" (:role %)))
       last
       :content
       str))

(defn factory-prompt?
  "True when the latest user turn asks to author a session tool."
  [text]
  (boolean (re-find #"(?i)add_two|session tool|tool_define|define an"
                    (str text))))

(defn- add-two-spec
  []
  {:name "add_two"
   :description "Add two integers"
   :input-schema "[:map [:a :int] [:b :int]]"
   :invoke "(fn [args _ctx] (str (+ (:a args) (:b args))))"})

(defn- tool-json
  [messages name]
  (->> messages
       (filter #(and (= "tool" (:role %)) (= name (:name %))))
       last
       :content
       parse-json))

(defn- factory-table
  [messages]
  (let [defined (tool-json messages "tool_define")
        tested (tool-json messages "tool_test")
        listed (tool-json messages "tool_list_runtime")
        added (last (filter #(and (= "tool" (:role %)) (= "add_two" (:name %)))
                            messages))]
    [{:step "tool_define"
      :ok (:ok defined)
      :tool (or (:tool-name defined) "add_two")}
     {:step "tool_test"
      :ok (:ok tested)
      :actual (:actual tested)}
     {:step "add_two"
      :result (or (:content added) "")}
     {:step "tool_list_runtime"
      :ephemeral (or (:ephemeral listed)
                     (get-in listed [:status :ephemeral])
                     [])}]))

(defn- factory-reply
  [model seen messages]
  (cond
    (not (contains? seen "tool_define"))
    (assistant-tools model [(tool-call "tool_define" (add-two-spec))])

    (not (contains? seen "tool_test"))
    (assistant-tools model [(tool-call "tool_test"
                                       {:name "add_two"
                                        :arguments {:a 1 :b 2}
                                        :expected-output "3"})])

    (not (contains? seen "add_two"))
    (assistant-tools model [(tool-call "add_two" {:a 10 :b 7})])

    (not (contains? seen "tool_list_runtime"))
    (assistant-tools model [(tool-call "tool_list_runtime" {})])

    (not (contains? seen "portal_submit"))
    (assistant-tools model [(tool-call "portal_submit"
                                       {:label "session tool add_two"
                                        :kind "table"
                                        :value (factory-table messages)})])

    :else
    (assistant-text
     model
     (str "Session tool add_two is live on this workbench session. "
          "I defined it, tested 1+2=3, called it with 10+7, and listed "
          "runtime tools. Table: " (cite-from messages)))))

(defn- file-index-reply
  [model seen messages]
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
          "listed file_edits. Table: " (cite-from messages)))))

(defn next-reply
  "Decide the next OpenAI-shaped chat completion from `messages`."
  [model messages]
  (let [seen (set (tool-names messages))]
    (if (factory-prompt? (last-user-text messages))
      (factory-reply model seen messages)
      (file-index-reply model seen messages))))

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
