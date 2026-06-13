(ns kschltz.agent.exchange-test
  "Tests for the default exchange chain assembly.

   Covers:
     - default chain stage order + schema validation
     - end-to-end exchange with the default stub LLM
     - end-to-end dispatch with a fake LLM that returns tool calls
     - sequential tool execution (fact-sequential-tools)
     - compose-context trim stub pin (memory-followup marker)
     - error-boundary handles errors so :leave stages still run
     - decoupling verification (no agent.loop dependency)
     - LlmClient boundary (no direct HTTP in interceptors)
     - bind-llm-client wires the agent's LlmClient into the chain"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.exchange :as exchange]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.interceptors.schema :as schema]
            [kschltz.agent.llm.client :refer [LlmClient]]
            [kschltz.agent.llm.client :as lcm-client]
            [malli.core :as m]))

;; ---- Fake LLM that returns tool calls ----

(defn- tool-calling-llm
  "Reify of the canonical LlmClient that returns the given tool calls
   with empty assistant text."
  [tool-calls]
  (reify LlmClient
    (-call [_client _req]
      {:choices [{:message {:role       "assistant"
                            :content    ""
                            :tool_calls tool-calls}}]
       :model "fake/v0"
       :stub? true})))

;; ---- Schema / order tests ----

(deftest chain-loads-in-correct-order
  (testing "default chain has the locked stage order"
    (is (= [::ix/error-boundary
            ::ix/bind-llm-client
            ::ix/compose-context
            ::ix/llm-call
            ::ix/parse-response
            ::ix/dispatch
            ::ix/store-exchange
            ::ix/deliver-responses
            ::ix/notify]
           (mapv :name exchange/default-exchange-chain)))))

(deftest every-stage-validates-against-interceptor-schema
  (is (= :ok (ix/check-stages))))

(deftest default-chain-first-element-validates
  (is (not (m/explain schema/Interceptor
                      (first exchange/default-exchange-chain)))))

;; ---- End-to-end through chain/execute (with stub LLM) ----

(defn- run-exchange
  "Execute the default chain with a stub LlmClient. Returns final ctx."
  ([user-text] (run-exchange user-text (lcm-client/stub-client)))
  ([user-text llm]
   (chain/execute
    {:agent/state        {:base-url "stub" :api-key nil :model "stub/v0"
                          :agent/system-message "you are a test agent"}
     :exchange/user-text user-text
     :llm/client         llm
     :exchange/session-id :test-session
     :exchange/user-msg-id (str (random-uuid))}
    exchange/default-exchange-chain)))

(deftest full-exchange-returns-stub-response
  (let [out (run-exchange "hello world")]
    (is (string? (:exchange/response out)))
    (is (str/starts-with? (:exchange/response out)
                          "lateralus-v2 stub LLM echoed:"))
    (is (some? (:memory/last-exchange out))
        "store-exchange leave stage records the exchange")
    (is (= :test-session (-> out :memory/last-exchange :session-id))
        "session-id threads through the chain")))

(deftest full-exchange-delivers-response
  (let [out (run-exchange "ping")]
    (is (vector? (:exchange/delivered out)))
    (is (= 1 (count (:exchange/delivered out))))
    (is (= :test-session (-> out :exchange/delivered first :session-id)))))

(deftest full-exchange-notifies-once
  (let [out (run-exchange "ping")]
    (is (= 1 (count (:exchange/notified out))))
    (is (= :complete (-> out :exchange/notified first :event)))))

;; ---- End-to-end dispatch with a fake LLM that produces tool calls ----

(deftest dispatch-end-to-end-with-tool-calls
  (let [calls [{:id "tc1" :name "echo" :args {:msg "hi"}}
               {:id "tc2" :name "echo" :args {:msg "bye"}}]
        out   (run-exchange "ping" (tool-calling-llm calls))]
    (is (= calls (:tool/calls out))
        "parse-response extracted the tool calls from the fake LLM response")
    (is (vector? (:tool/results out)))
    (is (= 2 (count (:tool/results out)))
        "dispatch recorded one result per call through the full chain")
    (is (every? #(= :not-implemented (:result %))
                (:tool/results out))
        "MVP stub dispatch returns :not-implemented for every call")
    (is (some? (:memory/last-exchange out))
        "store-exchange leave stage still ran (no errors raised)")
    (is (some? (:exchange/response out))
        "deliver-responses leave stage ran with the final response")))

;; ---- Sequential tool execution (fact-sequential-tools) ----

(deftest dispatch-uses-sequential-mapv
  (testing "dispatch records :tool/results via sequential mapv"
    (let [enter-fn (:enter ix/dispatch)
          ctx      {:agent/state {}
                    :tool/calls [{:id "1" :name "fake"}
                                 {:id "2" :name "fake"}
                                 {:id "3" :name "fake"}]}
          out      (enter-fn ctx)]
      (is (map? out))
      (is (vector? (:tool/results out)))
      (is (= 3 (count (:tool/results out))))
      (is (= ["1" "2" "3"] (mapv #(get-in % [:call :id]) (:tool/results out)))
          "result order matches input order (sequential)"))))

;; ---- compose-context stub pin ----

(deftest compose-context-trim-is-noop
  (testing "compose-context sets :compose/trimmed? to mark the no-op trim path"
    (let [enter-fn (:enter ix/compose-context)
          ctx      {:agent/state {:agent/system-message "sys"}
                    :exchange/user-text "hi"}
          out      (enter-fn ctx)]
      - "compose stage records the trim marker; the future
   history-trimming follow-up will replace or remove it")))

(deftest trim-history-stub-arity
  (testing "trim-history-stub is a no-op identity with arity 1 (memory follow-up keeps the arity)"
    (let [resolved (resolve 'kschltz.agent.interceptors/trim-history-stub)
          v        resolved
          arity    (-> v meta :arglists first count)]
      (is (some? resolved) "trim-history-stub is defined")
      (is (fn? @v) "trim-history-stub is a fn")
      (is (= 1 arity)
          "trim-history-stub takes exactly 1 arg (memory follow-up keeps the arity)")
      (is (= [:a :b] (@v [:a :b]))
          "trim-history-stub returns its input unchanged"))))

;; ---- error-boundary handles errors so :error/raised is observable ----

(deftest error-boundary-handles-and-observes
  (let [bomb       (ex-info "boom" {:chain/stage :enter})
        bomb-stage {:name ::bomb :enter (fn [_] (throw bomb))}
        ;; Note: stages AFTER bomb-stage never enter (the engine
        ;; stops the enter walk on first error). They are not in
        ;; the stack and therefore cannot run :leave. This matches
        ;; the v1 engine contract (see chain.clj ns docstring).
        chain      [ix/error-boundary
                    bomb-stage
                    ix/store-exchange
                    ix/deliver-responses
                    ix/notify]
        out        (chain/execute
                    {:exchange/session-id :test-session}
                    chain)]
    (is (some? (:error/raised out))
        "error-boundary annotates :error/raised on the final ctx")
    (is (= :enter (-> out :error/raised :stage))
        "stage is carried through the annotation")
    (is (identical? bomb (-> out :error/raised :exception)))
    (is (not (contains? out ::chain/error))
        "error-boundary cleared engine ::error so chain doesn't rethrow")))

;; ---- bind-llm-client: agent's client flows through to llm-call ----

(defn- marker-client []
  (reify LlmClient
    (-call [_client _req]
      {:choices [{:message {:role "assistant" :content "MARKER"}}]
       :model "marker/v0"
       :stub? true})))

(deftest bind-llm-client-copies-agent-client
  (testing "bind-llm-client copies :agent/llm-client onto ctx as :llm/client"
    (let [agent-client (marker-client)
          enter-fn     (:enter ix/bind-llm-client)
          ctx          {:agent/llm-client agent-client}
          out          (enter-fn ctx)]
      (is (identical? agent-client (:llm/client out))
          "agent's LlmClient is now visible to llm-call via ctx"))))

(deftest bind-llm-client-prefers-ctx-client
  (testing "an explicit :llm/client on ctx takes precedence over the agent's"
    (let [agent-client (marker-client)
          ctx-client   (marker-client)
          enter-fn     (:enter ix/bind-llm-client)
          ctx          {:agent/llm-client agent-client
                        :llm/client      ctx-client}
          out          (enter-fn ctx)]
      (is (identical? ctx-client (:llm/client out))
          "ctx-provided client wins (tests may inject a fake this way)"))))

(deftest agent-map-client-flows-into-exchange
  (testing "agent-map-configured LlmClient is what the chain uses end-to-end"
    ;; The marker client lives on the agent map (no Integrant call).
    ;; The per-exchange ctx carries ONLY :agent/llm-client (not
    ;; :llm/client). The bind-llm-client stage copies the agent's
    ;; client onto ctx, and llm-call invokes the marker.
    ;;
    ;; :embedder and :memory-backend are placeholders only — the
    ;; chain doesn't read them off the agent map during execute.
    (let [marker    (marker-client)
          agent-map {:agent/llm-client  marker
                     :embedder          :placeholder
                     :memory-backend    :placeholder
                     :assembled         []
                     :exchange-chain    exchange/default-exchange-chain}
          out       (chain/execute
                     {:agent/state        {:base-url "stub" :api-key nil :model "stub/v0"
                                           :agent/system-message "sys"}
                      :agent/llm-client   marker
                      :exchange/user-text "hello"
                      :exchange/session-id :test-session
                      :exchange/user-msg-id (str (random-uuid))}
                     (:exchange-chain agent-map))]
      (is (= "MARKER" (:exchange/response out))
          "the response came from the agent's LlmClient, not a fresh stub"))))

;; ---- Decoupling verification (plan Step 3 risk) ----

(deftest interceptors-do-not-depend-on-loop-clj
  (testing "no alias or refer targets kschltz.agent.loop"
    (let [ns-symbol (find-ns 'kschltz.agent.interceptors)
          aliases  (ns-aliases ns-symbol)
          refs     (ns-refers ns-symbol)
          all-keys (map (comp str key) (concat aliases refs))
          bad      (filter #(str/starts-with? % "kschltz.agent.loop")
                           all-keys)]
      (is (empty? bad)
          (str "no alias or refer targets kschltz.agent.loop; found: " (vec bad))))))

;; ---- LlmClient boundary: no direct HTTP in interceptors ----

(deftest interceptors-do-not-import-hato-or-http
  (testing "no http/hato dependency in the interceptor namespace"
    (let [ns-symbol (find-ns 'kschltz.agent.interceptors)
          aliases  (ns-aliases ns-symbol)]
      (is (not-any? (fn [[alias _sym]]
                      (let [s (str alias)]
                        (or (str/includes? s "hato")
                            (str/includes? s "http"))))
                    aliases)
          "interceptors never import hato/http directly; all I/O via LlmClient"))))
