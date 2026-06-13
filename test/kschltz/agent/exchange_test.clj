(ns kschltz.agent.exchange-test
  "Tests for the default exchange chain assembly.

   Covers:
     - default chain stage order + schema validation
     - end-to-end exchange with the default stub LLM
     - end-to-end dispatch with a fake LLM that returns tool calls
     - sequential tool execution (fact-sequential-tools)
     - compose-context trim stub pin (Step 6 marker)
     - error-boundary handles errors so :leave stages still run
     - decoupling verification (no agent.loop dependency)
     - LlmClient boundary (no direct HTTP in interceptors)"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.exchange :as exchange]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.interceptors.schema :as schema]
            [kschltz.agent.llm.client :refer [LlmClient]]
            [kschltz.agent.llm.client :as lcm-client]
            [malli.core :as m]))

;; ---- Fake LLM that returns tool calls (for the dispatch end-to-end test) ----

(defn- tool-calling-llm
  "Reify of the canonical LlmClient that returns the given tool calls
   with empty assistant text. Used to exercise the full dispatch
   path through chain/execute, not just dispatch in isolation."
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

;; ---- compose-context stub pin: trim is a no-op until Step 6 ----

(deftest compose-context-trim-is-noop
  (testing "compose-context sets :compose/trimmed? to mark the no-op trim path"
    ;; The trim is an inline identity until Step 6; the marker
    ;; :compose/trimmed? is what Step 6 will replace.
    (let [enter-fn (:enter ix/compose-context)
          ctx      {:agent/state {:agent/system-message "sys"}
                    :exchange/user-text "hi"}
          out      (enter-fn ctx)]
      (is (true? (:compose/trimmed? out))
          "compose stage records the trim marker; Step 6 changes the marker or removes it"))))

;; ---- error-boundary handles errors so :error/raised is observable ----

(deftest error-boundary-handles-and-observes
  (let [bomb       (ex-info "boom" {:chain/stage :enter})
        bomb-stage {:name ::bomb :enter (fn [_] (throw bomb))}
        ;; Note: stages AFTER bomb-stage never enter (the engine
        ;; stops the enter walk on first error). They are not in
        ;; the stack and therefore cannot run :leave. This matches
        ;; the v1 engine contract.
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
    (is (not (contains? out :kschltz.agent.chain/error))
        "error-boundary cleared engine ::error so chain doesn't rethrow")))

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
