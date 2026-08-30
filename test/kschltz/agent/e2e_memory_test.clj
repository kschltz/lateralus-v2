(ns kschltz.agent.e2e-memory-test
  "End-to-end integration test exercising a real HTTP LlmClient,
   the LangChain4j in-process embedder, and the Proximum memory
   backend together.

   Defaults to Ollama at http://localhost:11434/v1 with model
   glm5.1:cloud. Override via env vars:

     LATERALUS_E2E_BASE_URL   default http://localhost:11434/v1
     LATERALUS_E2E_MODEL      default glm5.1:cloud
     LATERALUS_E2E_FAKE=true  use the bundled fake LLM server instead
                              (deterministic assertions, no Ollama needed)

   The test verifies:
     1. A first exchange is answered by the real LLM (non-empty response).
     2. A second exchange in the same session sees recalled prior
        messages in the composed LLM request.
     3. A second exchange in a different session does not see the
        first session's messages.

   This namespace is tagged ^:e2e so it can be run separately:

     clojure -M:e2e

   When run as part of the default suite, the tests skip gracefully
   if Ollama is not reachable and LATERALUS_E2E_FAKE is unset."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hato.client :as http]
            [integrant.core :as ig]
            [kschltz.agent.cli :as cli]
            [kschltz.agent.llm.schemas :as schemas]
            [kschltz.agent.runtime :as runtime]
            [ring.adapter.jetty :as jetty]))

(defn- e2e-config
  "Return the base URL and model for the e2e LLM. Defaults to a
   local Ollama OpenAI-compatible endpoint; override with env vars."
  []
  {:base-url (or (System/getenv "LATERALUS_E2E_BASE_URL")
                 "http://localhost:11434/v1")
   :model    (or (System/getenv "LATERALUS_E2E_MODEL")
                 "glm5.1:cloud")})

(defn- fake? []
  (= "true" (System/getenv "LATERALUS_E2E_FAKE")))

(defn- ollama-reachable?
  "Cheap health check against Ollama's native /api/tags endpoint."
  [base-url]
  (let [tags-url (str (str/replace base-url #"/v1$" "") "/api/tags")]
    (try
      (= 200 (:status (http/get tags-url
                                {:connect-timeout 2000
                                 :request-timeout 2000
                                 :throw-exceptions? false})))
      (catch Throwable _ false))))

(defn- ollama-model-available?
  "Check whether the configured model is present in Ollama's /api/tags."
  [base-url model]
  (let [tags-url (str (str/replace base-url #"/v1$" "") "/api/tags")]
    (try
      (let [resp (http/get tags-url
                           {:connect-timeout 2000
                            :request-timeout 2000
                            :throw-exceptions? false})
            body (json/parse-string (:body resp) true)]
        (some #(= model (:name %)) (:models body)))
      (catch Throwable _ false))))

(defn- recall-aware-handler
  "A ring handler that echoes the last user message, but also inspects
   the request messages for any '[recall] ' prefixed content. If recall
   is present, the response mentions it; if not, it answers as if no
   context was given."
  [req]
  (let [body-str  (slurp (:body req))
        body      (json/parse-string body-str true)
        messages  (:messages body)
        last-user (some #(when (= "user" (:role %)) (:content %))
                        (reverse messages))
        recalls   (->> messages
                       (keep #(when (= "system" (:role %)) (:content %)))
                       (mapcat #(re-seq #"(?m)^\[recall\] (.+)$" (str %)))
                       (map second))
        answer    (if (seq recalls)
                    (str "I remember: " (first recalls))
                    (str "No memory yet: " last-user))
        streaming? (true? (:stream body))
        response-body
        (if streaming?
          (str "data: "
               (json/generate-string
                {:model (:model body)
                 :choices [{:delta {:role "assistant" :content answer}
                            :finish_reason "stop"}]})
               "\n\ndata: [DONE]\n\n")
          (json/generate-string
           {:model (:model body)
            :choices [{:message {:role "assistant" :content answer}}]}))
        response-content-type
        (if streaming? "text/event-stream" "application/json")]
    {:status 200
     :headers {"Content-Type" response-content-type}
     :body response-body}))

(def ^:private state (atom nil))

(use-fixtures :once
  (fn [f]
    (reset! state nil)
    (try
      (cond
        (fake?)
        (let [server (jetty/run-jetty recall-aware-handler
                                      {:port 0 :host "127.0.0.1" :join? false})
              port   (-> server .getURI .getPort)]
          (reset! state {:server   server
                         :base-url (str "http://127.0.0.1:" port)
                         :model    "fake-llm"})
          (f))

        :else
        (let [{:keys [base-url model]} (e2e-config)]
          (cond
            (not (ollama-reachable? base-url))
            (do (println "Skipping e2e-memory tests: Ollama not reachable at" base-url
                         "(set LATERALUS_E2E_FAKE=true for deterministic fake-server mode)")
                (is true "Ollama not available; e2e tests skipped"))

            (not (ollama-model-available? base-url model))
            (do (println "Skipping e2e-memory tests: model" model
                         "not found in Ollama at" base-url
                         "(pull it first or set LATERALUS_E2E_FAKE=true)")
                (is true (str "Ollama model " model " not available; e2e tests skipped")))

            :else
            (do (reset! state {:base-url base-url :model model})
                (f)))))
      (finally
        (when-let [server (:server @state)]
          (try (.stop server) (catch Throwable _)))))))

(defn- with-e2e-system
  "Build and initialize an Integrant system wired to the configured
   e2e LLM, the LangChain4j embedder, and the Proximum in-memory
   backend. Returns the started system."
  []
  (let [{:keys [base-url model]} @state
        sys (-> (cli/build-system {})
                (assoc-in [:lateralus/llm-client]
                          {:impl :http
                           :base-url base-url
                           :api-key (System/getenv "LATERALUS_E2E_API_KEY")
                           :model model})
                (assoc-in [:lateralus/llm-config]
                          {:base-url base-url
                           :model model})
                (assoc-in [:lateralus/memory-backend :impl] :proximum)
                ig/init)]
    sys))

(deftest ^:e2e e2e-memory-recalls-within-session
  (testing "real HTTP LLM + LangChain4j + Proximum recall across two exchanges"
    (let [sys    (with-e2e-system)
          agent  (:lateralus/agent sys)
          rt     (runtime/start agent "mem-session")
          out1   (runtime/send-message rt "My favorite color is blue")
          out2   (runtime/send-message rt "What is my favorite color?")
          _      (runtime/stop rt)
          _      (ig/halt! sys)]
      (is (some? (:llm/response out1))
          "first exchange produced an LLM response")
      (is (seq (schemas/extract-text (:llm/response out1)))
          "first exchange produced a non-empty real LLM response")
      (is (some #(and (= "system" (:role %))
                      (str/starts-with? (:content %) "[recall] ")
                      (str/includes? (:content %) "blue"))
                (-> out2 :llm/request :messages))
          "second LLM request contains a recalled message about blue")
      ;; Deterministic assertion only when using the bundled fake server.
      ;; Real local/cloud models may or may not echo the recalled fact in
      ;; their answer; the request-shape assertion above is the real gate.
      (when (fake?)
        (is (= "I remember: My favorite color is blue"
               (schemas/extract-text (:llm/response out2)))
            "fake LLM echoes the recalled fact")))))

(deftest ^:e2e e2e-memory-isolated-by-session
  (testing "memory recall is scoped to the session id"
    (let [sys     (with-e2e-system)
          agent   (:lateralus/agent sys)
          rt-a    (runtime/start agent "session-a")
          rt-b    (runtime/start agent "session-b")
          _       (runtime/send-message rt-a "secret for A")
          out-b   (runtime/send-message rt-b "What is the secret?")
          _       (runtime/stop rt-a)
          _       (runtime/stop rt-b)
          _       (ig/halt! sys)]
      (is (not (some #(str/includes? (:content %) "secret for A")
                     (-> out-b :llm/request :messages)))
          "session-b's LLM request does not recall session-a's message"))))

(deftest ^:e2e e2e-recalled-messages-appear-in-llm-request
  (testing "recalled content is prefixed and injected into the second request"
    (let [sys    (with-e2e-system)
          agent  (:lateralus/agent sys)
          rt     (runtime/start agent "verify-request")
          _      (runtime/send-message rt "Remember the number 42")
          out    (runtime/send-message rt "What is the number?")
          msgs   (-> out :llm/request :messages)
          _      (runtime/stop rt)
          _      (ig/halt! sys)]
      (is (some #(and (= "system" (:role %))
                      (str/starts-with? (:content %) "[recall] ")
                      (str/includes? (:content %) "42"))
                msgs)
          "the second LLM request contains a prefixed recalled message"))))
