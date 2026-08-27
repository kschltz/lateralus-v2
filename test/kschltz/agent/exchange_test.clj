(ns kschltz.agent.exchange-test
  "Tests for the default exchange chain assembly.

   Covers:
     - default chain stage order + schema validation
     - end-to-end exchange with the default stub LLM
     - end-to-end dispatch with a fake LLM that returns tool calls
     - sequential tool execution (fact-sequential-tools)
     - compose-context trim stub pin (memory-followup marker)
     - error-boundary handles errors so :leave stages still run
     - LlmClient boundary (no direct HTTP in interceptors)
     - Pre-wired dependencies flow into the exchange context"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.interceptors.schema :as schema]
            [kschltz.agent.llm.client :refer [LlmClient]]
            [kschltz.agent.llm.client :as lcm-client]
            [kschltz.agent.logging :as logging]
            [kschltz.agent.loop :as loop]
            [kschltz.agent.loop.retry :as retry]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.plugins.tools :as plugins.tools]
            [kschltz.agent.tools.filesystem :as tools.filesystem]
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

;; ---- Helpers ----

(defn- default-exchange-chain []
  (plugin/assemble-chain [(plugins.base/base-plugin)
                          (plugins.tools/tools-plugin)]))

;; ---- Schema / order tests ----

(deftest chain-loads-in-correct-order
  (testing "default chain has the locked stage order"
    (is (= [::logging/logging
            ::ix/error-boundary
            :kschltz.agent.plugins.tools/seed-registry
            :kschltz.agent.runtime-reload/reload-notice
            ::ix/compose-context
            ::loop/inject-tools
            ::loop/llm-call-with-self-heal
            ::ix/llm-call
            ::ix/parse-response
            ::loop/dispatch-tools
            :kschltz.agent.transitions.interceptors/harvest-transitions
            :kschltz.agent.transitions.interceptors/apply-transitions
            ::retry/retry-now-available
            ::retry/nudge-untested-runtime-tools
            ::loop/compose-tool-results
            ::loop/tool-loop
            ::loop/ensure-text-response
            ::ix/summarize-history
            ::ix/store-exchange
            ::ix/deliver-responses
            ::ix/notify]
           (mapv :name (default-exchange-chain))))))

(deftest every-stage-validates-against-interceptor-schema
  (is (= :ok (ix/check-stages))))

(deftest default-chain-first-element-validates
  (is (not (m/explain schema/Interceptor
                      (first (default-exchange-chain))))))

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
    (default-exchange-chain))))

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
  (let [calls [{:id "tc1" :type "function" :function {:name "echo" :arguments "{\"msg\":\"hi\"}"}}
               {:id "tc2" :type "function" :function {:name "echo" :arguments "{\"msg\":\"bye\"}"}}]
        out   (run-exchange "ping" (tool-calling-llm calls))]
    (is (= calls (get-in out [:llm/response :choices 0 :message :tool_calls]))
        "the LLM response still carries the tool calls after finalize")
    (is (vector? (:tool/results out)))
    (is (= 2 (count (:tool/results out)))
        "dispatch recorded one result per call through the full chain")
    (is (every? #(str/includes? (:result %) "not available")
                (:tool/results out))
        "unregistered tools return a model-visible unavailable message")
    (is (some? (:memory/last-exchange out))
        "store-exchange leave stage still ran (no errors raised)")
    (is (some? (:exchange/response out))
        "deliver-responses leave stage ran with the final response")))

(deftest registered-tool-executes-end-to-end
  (testing "a registered filesystem tool is executed by the default chain"
    (let [tmp      (java.io.File/createTempFile "lateralus-test" ".txt")
          _        (spit tmp "hello from filesystem tool")
          _        (.deleteOnExit tmp)
          registry (tools.filesystem/filesystem-registry {:workspace-root (.getParent tmp)})
          calls    [{:id "tc1" :type "function" :function {:name "file_read"
                                                           :arguments (format "{\"path\":\"%s\"}"
                                                                              (.getName tmp))}}]
          out      (chain/execute
                    {:agent/state        {:base-url "stub" :api-key nil :model "fake/v0"
                                          :agent/system-message "you are a test agent"}
                     :exchange/user-text "read the test file"
                     :llm/client         (tool-calling-llm calls)
                     :exchange/session-id :test-session
                     :exchange/user-msg-id (str (random-uuid))}
                    (plugin/assemble-chain [(plugins.base/base-plugin)
                                            (plugins.tools/tools-plugin registry)]))]
      (is (= 1 (count (:tool/results out))))
      (is (str/includes? (-> out :tool/results first :result) "hello from filesystem tool")
          "file_read Tool returned the test file content"))))

;; ---- compose-context trim stub pin ----

(deftest compose-context-trim-is-noop
  (testing "compose-context sets :compose/trimmed? to mark the no-op trim path"
    (let [enter-fn (:enter ix/compose-context)
          ctx      {:agent/state {:agent/system-message "sys"}
                    :exchange/user-text "hi"}
          out      (enter-fn ctx)]
      (is (true? (:compose/trimmed? out))
          "compose stage records the trim marker; the future
   history-trimming follow-up will replace or remove it"))))

(deftest trim-history-bounds-growth
  (testing "trim-history keeps the leading system message, caps the body
            to max-history-entries, never drops the most recent user turn,
            truncates oversized tool content, and honors per-tool caps when
            the message carries :name"
    (let [resolved (resolve 'kschltz.agent.interceptors/trim-history)
          v        resolved
          arities  (set (map count (:arglists (meta v))))]
      (is (some? resolved) "trim-history is defined")
      (is (fn? @v) "trim-history is a fn")
      (is (= #{1 2} arities) "trim-history supports 1-arity and caps 2-arity")
      ;; Keeps a small vector as-is.
      (let [small [{:role "system" :content "sys"}
                   {:role "user" :content "hi"}
                   {:role "assistant" :content "yo"}]]
        (is (= small (@v small)) "small history is returned unchanged"))
      ;; Truncates an oversized :role "tool" :content with the marker.
      (let [big (apply str (repeat 3000 "x"))
            trimmed (@v [{:role "tool" :tool_call_id "t1" :content big}])]
        (is (str/ends-with? (-> trimmed first :content) "...[trimmed]")
            "oversized tool content is truncated with a marker"))
      ;; Per-tool cap applies when :name is stamped.
      (let [big (apply str (repeat 5000 "x"))
            trimmed (@v [{:role "tool" :tool_call_id "t1"
                          :name "clojure_eval" :content big}]
                        {"clojure_eval" 12000})]
        (is (= 5000 (count (:content (first trimmed))))
            "named tool content under its per-tool cap is not truncated"))
      ;; Never drops the most recent user turn even when the window would cut it.
      (let [msgs (into [{:role "system" :content "sys"}]
                     (concat (repeat 50 {:role "assistant" :content "x"})
                             [{:role "user" :content "latest user"}]))
            out (@v msgs)]
        (is (some #(= "latest user" (:content %)) out)
            "the most recent user turn survives the cap")))))

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

;; ---- Pre-wired client: agent's client flows through to llm-call ----

(defn- marker-client []
  (reify LlmClient
    (-call [_client _req]
      {:choices [{:message {:role "assistant" :content "MARKER"}}]
       :model "marker/v0"
       :stub? true})))

(deftest agent-map-client-flows-into-exchange
  (testing "agent-map-configured LlmClient is what the chain uses end-to-end"
    ;; The marker client lives on the agent map. The runtime pre-wires
    ;; it onto ctx as :llm/client, so llm-call invokes the marker.
    (let [marker    (marker-client)
          agent-map {:agent/llm-client  marker
                     :embedder          :placeholder
                     :memory-backend    :placeholder
                     :assembled         []
                     :exchange-chain    (default-exchange-chain)}
          out       (chain/execute
                     {:agent/state        {:base-url "stub" :api-key nil :model "stub/v0"
                                           :agent/system-message "sys"}
                      :llm/client         marker
                      :exchange/user-text "hello"
                      :exchange/session-id :test-session
                      :exchange/user-msg-id (str (random-uuid))}
                     (:exchange-chain agent-map))]
      (is (= "MARKER" (:exchange/response out))
          "the response came from the agent's LlmClient, not a fresh stub"))))

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
