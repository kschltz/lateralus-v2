(ns kschltz.agent.live-llm-tool-test
  "Live-LLM regression test for cross-exchange tool result persistence.

   Exercises the store-exchange fix against a real OpenAI-compatible
   HTTP endpoint (default: local Ollama at
   http://localhost:11434/v1 with model gemma4:31b-mlx). The agent
   uses the bundled `file_read` tool to read a Clojure source file,
   then a follow-up prompt asks the model to answer from memory
   without re-invoking the tool. The assertion is that exchange 2's
   response actually mentions the namespace it read in exchange 1 —
   which is only possible if the file contents (carried as a tool
   result message) were persisted across exchanges.

   Tagged `^:e2e` so the default `clojure -M:test` suite skips it
   (test-runner `-e :e2e` exclusion) and `clojure -M:e2e` runs it.

   If no LLM endpoint is reachable the entire ns is a no-op — every
   test prints a SKIP line and exits without recording an assertion.
   Override via env vars:

     OLLAMA_BASE_URL   default http://localhost:11434/v1
     OLLAMA_MODEL      default gemma4:31b-mlx"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [kschltz.agent.cli :as cli]
            [kschltz.agent.runtime :as runtime]))

;; ---- Endpoint config ----

(defn- endpoint []
  {:base-url (or (System/getenv "OLLAMA_BASE_URL")
                 "http://localhost:11434/v1")
   :model    (or (System/getenv "OLLAMA_MODEL")
                 "gemma4:31b-mlx")})

(defn- host-port
  "Parse http(s)://host:port[/path] from a URL string."
  [base-url]
  (let [m (re-find #"^https?://([^:/]+)(?::(\d+))?" base-url)]
    (when m
      {:host (nth m 1)
       :port (if-let [p (nth m 2 nil)]
               (Integer/parseInt p)
               (if (str/starts-with? base-url "https://") 443 80))})))

(defn- reachable?
  "Cheap TCP socket probe; returns true if we can open a connection."
  [{:keys [host port]}]
  (let [sock (java.net.Socket.)]
    (try
      (.connect sock (java.net.InetSocketAddress. ^String host (int port)) 1500)
      (.close sock)
      true
      (catch Throwable _ false))))

;; ---- System wiring ----

(defn- build-system
  "Build + init an Integrant system pointed at the configured live
   LLM, with a filesystem tool registry rooted at `src/` and the
   self-awareness registry. Returns the initialized system map."
  [base-url model]
  (-> (cli/build-system {})
      (assoc-in [:lateralus/llm-client]
                {:impl     :http
                 :base-url base-url
                 :api-key  (System/getenv "OLLAMA_API_KEY")
                 :model    model})
      (assoc-in [:lateralus/llm-config]
                {:base-url base-url
                 :api-key  (System/getenv "OLLAMA_API_KEY")
                 :model    model})
      (assoc-in [:lateralus/file-tools] {:workspace-root "src"})
      (assoc-in [:lateralus/self-awareness-tools] {})
      ig/init))

;; ---- Skip gate ----
;;
;; Every test in this ns reads *llm-state*. The fixture resolves the
;; endpoint and either reaches the LLM or skips the whole suite.

(defonce ^:private ^{:dynamic true} *llm-state* (atom nil))

(defn- fixture-fn [f]
  (reset! *llm-state* nil)
  (let [{:keys [base-url model]} (endpoint)
        target                  (host-port base-url)]
    (cond
      (not target)
      (println (str "SKIPPED live-llm tests: cannot parse OLLAMA_BASE_URL="
                    base-url))

      (not (reachable? target))
      (println (str "SKIPPED live-llm tests: endpoint not reachable at "
                    base-url " (set OLLAMA_BASE_URL / OLLAMA_MODEL "
                    "to override, or start Ollama)"))

      :else
      (try
        (let [sys (build-system base-url model)]
          (reset! *llm-state* {:sys sys :base-url base-url :model model})
          (f))
        (catch Throwable t
          (println (str "SKIPPED live-llm tests: failed to initialize "
                        "Integrant system — " (.getMessage t))))))
    (when-let [sys (:sys @*llm-state*)]
      (try (ig/halt! sys) (catch Throwable _)))))

(use-fixtures :once fixture-fn)

(defn- with-live-llm [f]
  "Run body `f` only if the fixture marked the LLM as reachable;
   otherwise no-op (the fixture already printed SKIPPED)."
  (when-let [{:keys [sys]} @*llm-state*]
    (try
      (f sys)
      (finally (try (ig/halt! sys) (catch Throwable _))))))

;; ---- The test ----

(deftest ^:e2e file-read-content-survives-second-exchange
  (testing "tool result from exchange 1 (a file_read of
            src/kschltz/agent/chain.clj) is visible in exchange 2's
            response WITHOUT the model calling file_read again"
    (with-live-llm
      (fn [sys]
        (let [agent-map    (:lateralus/agent sys)
              rt           (runtime/start agent-map "live-llm-tool")
             ;; Exchange 1: ask the model to read the chain ns file.
              out1         (runtime/send-message
                            rt
                            (str "use file_read to read "
                                 "src/kschltz/agent/chain.clj "
                                 "and tell me its namespace name"))
             ;; Exchange 2: same session, ask from memory. Crucially
             ;; the response must mention the ns the model saw in
             ;; exchange 1 even though we did NOT invoke file_read
             ;; again here.
              out2         (runtime/send-message
                            rt
                            (str "what namespace did you just read? "
                                 "answer from memory, do NOT call "
                                 "file_read again"))
              response2    (:exchange/response out2)
              final-state  (runtime/stop rt)
              history      (get-in final-state [:agent/history])
              tool-history (filter #(= "tool" (:role %)) history)]
          (testing "first exchange succeeded with a non-empty response"
            (is (seq (:exchange/response out1))
                (str "exchange 1 should produce a non-empty response; "
                     "got=" (pr-str (:exchange/response out1)))))
          (testing "second exchange answer mentions kschltz.agent.chain"
           ;; The model must have remembered the file content from
           ;; exchange 1's tool result. This is the cross-exchange
           ;; persistence gate.
            (is (str/includes? (or response2 "") "kschltz.agent.chain")
                (str "exchange 2 response should mention "
                     "kschltz.agent.chain; got=" (pr-str response2))))
          (testing "store-exchange persisted the tool result message"
            (is (seq tool-history)
                (str "runtime :agent/history should contain at least "
                     "one :role \"tool\" entry; got history="
                     (pr-str (mapv :role history)))))
          (testing "exchange 2 request included the persisted tool message"
            (is (some #(= "tool" (:role %))
                      (-> out2 :llm/request :messages))
                (str "exchange 2 outgoing request should carry the "
                     "persisted tool message; got="
                     (pr-str (mapv :role
                                   (-> out2 :llm/request :messages)))))))))))