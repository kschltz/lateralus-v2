(ns kschltz.agent.llm.http-test
  "End-to-end tests for the real HTTP-backed LlmClient.

   Spins up a tiny ring/jetty server on a random port that
   pretends to be an OpenAI-compatible chat completions endpoint.
   Then exercises the http-client against it:
     - successful 200 with text → round-trip extracts text
     - successful 200 with tool_calls → round-trip extracts calls
     - 4xx with JSON error body → throws ex-info with :kind :http-error
     - request shape validation (Malli decode on the way out)
     - default timeouts (pin tests + smoke test against a live server)

   No real network is touched. The server is bound to 127.0.0.1
   on an OS-assigned port and shut down at the end of each test."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.llm.client :as lcm-client]
            [kschltz.agent.llm.http :as lcm-http]
            [kschltz.agent.llm.schemas :as schemas]
            [kschltz.agent.llm.stream :as llm.stream]
            [ring.adapter.jetty :as jetty]))

;; ---- Fake OpenAI-compatible server ----

(defn- echo-handler
  "A ring handler that echoes the last user message back. If the
   request body's `:model` is 'tool-model', includes a tool_call.

   The body is slurped first because newer ring/jetty serves
   the body as an HttpInput stream, not a String."
  [req]
  (let [body-str  (slurp (:body req))
        body      (json/parse-string body-str true)
        messages  (:messages body)
        last-msg  (when (seq messages) (last messages))
        last-text (or (:content last-msg) "")
        model     (:model body)]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string
            {:model model
             :choices [{:message
                        {:role "assistant"
                         :content last-text
                         :tool_calls (when (= model "tool-model")
                                       [{:id "t1" :type "function"
                                         :function {:name "echo"
                                                    :arguments "{}"}}])}}]})}))

(defn- auth-error-handler
  "A ring handler that returns 401 for any request."
  [req]
  (slurp (:body req))
  {:status 401
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string
          {:error {:message "invalid api key" :type "auth_error"}})})

(defn with-fake-server
  "Run `f` with a jetty server running on 127.0.0.1:<random>
   serving `handler`. Returns whatever `f` returns."
  [handler f]
  (let [server (jetty/run-jetty handler
                            {:port 0 :host "127.0.0.1" :join? false})]
    (try
      (let [port (-> server .getURI .getPort)]
        (f port))
      (finally (.stop server)))))

;; ---- Tests ----

(deftest roundtrip-text-echo
  (with-fake-server
    echo-handler
    (fn [port]
      (let [client (lcm-http/http-client
                    {:base-url (str "http://127.0.0.1:" port)
                     :model    "echo-model"})
            resp   (lcm-client/-call client
                                     {:model    "echo-model"
                                      :messages [{:role "user" :content "hi"}]})]
        (is (= "echo-model" (schemas/extract-model resp)))
        (is (= "hi" (schemas/extract-text resp)))
        (is (empty? (schemas/extract-tool-calls resp)))))))

(deftest roundtrip-tool-calls
  (with-fake-server
    echo-handler
    (fn [port]
      (let [client (lcm-http/http-client
                    {:base-url (str "http://127.0.0.1:" port)
                     :model    "tool-model"})
            resp   (lcm-client/-call client
                                     {:model    "tool-model"
                                      :messages [{:role "user" :content "call"}]})]
        (is (= 1 (count (schemas/extract-tool-calls resp))))
        (is (= "echo"
               (get-in resp [:choices 0 :message :tool_calls 0 :function :name])))))))

(deftest post-chat-json-encodes-pattern-in-tool-schema
  (with-fake-server
    echo-handler
    (fn [port]
      (let [client (lcm-http/http-client
                    {:base-url (str "http://127.0.0.1:" port)
                     :model    "echo-model"})
            resp   (lcm-client/-call
                    client
                    {:model    "echo-model"
                     :messages [{:role "user" :content "hi"}]
                     :tools    [{:type "function"
                                 :function {:name "web_search"
                                            :description "search"
                                            :parameters {:type "object"
                                                         :pattern (re-pattern "\\S")}}}]})]
        (is (= "hi" (schemas/extract-text resp)))))))

(deftest http-error-throws-structured-ex-info
  (with-fake-server
    auth-error-handler
    (fn [port]
      (let [client (lcm-http/http-client
                    {:base-url (str "http://127.0.0.1:" port)
                     :model    "err-model"})]
        (try
          (lcm-client/-call client
                            {:model    "err-model"
                             :messages [{:role "user" :content "x"}]})
          (is false "expected throw")
          (catch clojure.lang.ExceptionInfo e
            (let [d (ex-data e)]
              (is (= :http-error (:kind d)))
              (is (= 401 (:status d)))
              (is (= "invalid api key"
                     (get-in d [:body :error :message]))))))))))

(deftest malli-rejects-malformed-request
  ;; The client is constructed with a base-url but no :model, so
  ;; the per-call request (also no :model) fails Malli validation
  ;; on the way out — before any HTTP work happens.
  (let [client (lcm-http/http-client
                {:base-url "http://127.0.0.1:1"})]  ; never used
    (try
      (lcm-client/-call client
                        {:messages [{:role "user" :content "x"}]}) ; no :model
      (is false "expected throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :request (:where (ex-data e))))
        (is (vector? (get (ex-data e) :problems)))))))

(deftest connect-timeout-defaults
  (testing "default connect timeout is 10s (v1 lesson)"
    (is (= 10000 lcm-http/default-connect-timeout-ms)))
  (testing "default request timeout is 60s"
    (is (= 60000 lcm-http/default-request-timeout-ms)))
  (testing "smoke test: opts without :connect-timeout-ms still work"
    (with-fake-server
      echo-handler
      (fn [port]
        (let [client (lcm-http/http-client
                      {:base-url (str "http://127.0.0.1:" port)
                       :model    "m"})
              resp   (lcm-client/-call client
                                       {:model    "m"
                                        :messages [{:role "user" :content "ok"}]})]
          (is (= "ok" (schemas/extract-text resp))))))))
(deftest merge-ollama-model-lists-tags-cloud-for-local-gateway
  (is (= ["laguna-s-2.1:latest" "ornith:35b" "deepseek-v4-flash:cloud" "glm-5.2:cloud"]
         (lcm-http/merge-ollama-model-lists
          ["laguna-s-2.1:latest" "ornith:35b" "deepseek-v4-flash:cloud"]
          ["deepseek-v4-flash" "glm-5.2"]
          {:cloud-suffix? true})))
  (is (= ["deepseek-v4-flash" "glm-5.2"]
         (lcm-http/merge-ollama-model-lists
          []
          ["glm-5.2" "deepseek-v4-flash"]
          {:cloud-suffix? false}))))

(deftest preferred-default-model-skips-cloud-and-embed
  (is (= "laguna-s-2.1:latest"
         (lcm-http/preferred-default-model
          ["deepseek-v4-flash:cloud" "nomic-embed-text:latest" "laguna-s-2.1:latest"]))))

(deftest normalize-base-url-strips-trailing-slashes
  (is (= "http://localhost:11434/v1"
         (lcm-http/normalize-base-url "http://localhost:11434/v1/")))
  (is (= "http://localhost:11434/v1"
         (lcm-http/normalize-base-url "http://localhost:11434/v1///"))))

(deftest models-url-tolerates-trailing-slash
  (testing "trailing slash must not produce //v1/models (Ollama 307)"
    (is (= "http://localhost:11434/v1/models"
           (lcm-http/models-url "http://localhost:11434/v1/")))
    (is (= "http://localhost:11434/v1/models"
           (lcm-http/models-url "http://localhost:11434/")))
    (is (= "http://localhost:11434/v1/models"
           (lcm-http/models-url "http://localhost:11434/v1")))
    (is (= "https://ollama.com/v1/models"
           (lcm-http/models-url "https://ollama.com/v1/")))))

(deftest resolve-base-url-rewrites-localhost-in-docker
  (let [docker (fn [k] (get {"LATERALUS_IN_DOCKER" "1"
                             "LATERALUS_DOCKER_OLLAMA_URL" "http://ollama:11434/v1"}
                            k))
        host (fn [_] nil)]
    (is (= "http://ollama:11434/v1"
           (lcm-http/resolve-base-url "http://localhost:11434/v1" docker)))
    (is (= "http://ollama:11434/v1"
           (lcm-http/resolve-base-url "http://127.0.0.1:11434/v1/" docker)))
    (is (= "http://localhost:11434/v1"
           (lcm-http/resolve-base-url "http://localhost:11434/v1" host)))
    (is (= "https://api.cerebras.ai/v1"
           (lcm-http/resolve-base-url "https://api.cerebras.ai/v1" docker)))))

(deftest merge-adjacent-assistant-messages-collapses-trailing-pair
  (let [out (lcm-http/merge-adjacent-assistant-messages
             [{:role "user" :content "go"}
              {:role "assistant" :content "Here's the plan."}
              {:role "assistant" :content "Let me give an honest answer."}])]
    (is (= ["user" "assistant"] (mapv :role out)))
    (is (str/includes? (:content (last out)) "Here's the plan."))
    (is (str/includes? (:content (last out)) "Let me give an honest answer."))))

(deftest normalize-chat-messages-system-then-merged-assistants
  (let [out (lcm-http/normalize-chat-messages
             [{:role "system" :content "base"}
              {:role "user" :content "q"}
              {:role "assistant" :content "a1"}
              {:role "system" :content "nudge"}
              {:role "assistant" :content "a2"}])]
    (is (= "system" (:role (first out))))
    (is (= 1 (count (filter #(= "system" (:role %)) out))))
    (is (= ["user" "assistant"] (mapv :role (rest out))))
    (is (str/includes? (:content (last out)) "a1"))
    (is (str/includes? (:content (last out)) "a2"))))

(deftest coalesce-system-messages-single-leading-system
  (testing "Qwen/Ollama templates reject any system message that is not first"
    (let [out (lcm-http/coalesce-system-messages
               [{:role "system" :content "base"}
                {:role "system" :content "[recall] hi"}
                {:role "user" :content "hi"}
                {:role "assistant" :content "hello"}
                {:role "system" :content "Call the tools now."}
                {:role "user" :content "so?"}])]
      (is (= "system" (:role (first out))))
      (is (= 1 (count (filter #(= "system" (:role %)) out))))
      (is (str/includes? (:content (first out)) "base"))
      (is (str/includes? (:content (first out)) "[recall] hi"))
      (is (str/includes? (:content (first out)) "Call the tools now."))
      (is (= ["user" "assistant" "user"] (mapv :role (rest out)))))))

(deftest post-chat-sends-one-leading-system
  (let [seen (atom nil)]
    (with-fake-server
      (fn [req]
        (reset! seen (json/parse-string (slurp (:body req)) true))
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string
                {:model "echo-model"
                 :choices [{:message {:role "assistant" :content "ok"}}]})})
      (fn [port]
        (let [client (lcm-http/http-client
                      {:base-url (str "http://127.0.0.1:" port)
                       :model    "echo-model"})]
          (lcm-client/-call client
                            {:model "echo-model"
                             :messages [{:role "system" :content "sys"}
                                        {:role "user" :content "hi"}
                                        {:role "assistant" :content "hello"}
                                        {:role "system" :content "[recall] hi"}
                                        {:role "user" :content "so?"}]})
          (let [msgs (:messages @seen)]
            (is (= "system" (:role (first msgs))))
            (is (= 1 (count (filter #(= "system" (:role %)) msgs))))
            (is (str/includes? (:content (first msgs)) "[recall] hi"))))))))

(defn- sse-echo-handler
  [req]
  (let [body (json/parse-string (slurp (:body req)) true)
        text (or (:content (last (:messages body))) "")]
    {:status 200
     :headers {"Content-Type" "text/event-stream"}
     :body (str "data: " (json/generate-string
                          {:model (:model body)
                           :choices [{:delta {:content text}}]})
                "\n"
                "data: [DONE]\n")}))

(deftest roundtrip-stream-text
  (with-fake-server
    sse-echo-handler
    (fn [port]
      (let [client (lcm-http/http-client
                    {:base-url (str "http://127.0.0.1:" port)
                     :model    "echo-model"})
            events (atom [])
            resp   (llm.stream/-call-stream
                    client
                    {:model "echo-model"
                     :messages [{:role "user" :content "stream-hi"}]}
                    #(swap! events conj %))]
        (is (llm.stream/streamable? client))
        (is (= "stream-hi" (schemas/extract-text resp)))
        (is (some #{:text-delta} (map :type @events)))
        (is (some #{:llm-done} (map :type @events)))))))
