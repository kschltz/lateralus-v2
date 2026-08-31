(ns kschltz.agent.workbench.loop-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.llm.client :as llm.client]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.workbench.hub :as hub]
            [kschltz.agent.workbench.loop :as loop]
            [kschltz.agent.workbench.protocol :as proto]))

(deftype SessionWorkbench [h closed?]
  clojure.lang.ILookup
  (valAt [_ k]
    (case k
      :hub h
      :session-store nil
      nil))
  (valAt [this k not-found]
    (or (.valAt this k) not-found))
  proto/Workbench
  (-url [_] "http://unit.test")
  (-portal-url [_] nil)
  (-publish! [_ event] (hub/publish-turn! h event))
  (-await-human! [_ opts] (hub/await-human! h opts))
  (-attach-selection! [_] nil)
  (-submit-portal! [_ _ _] nil)
  (-clear-portal! [_] {:ok true})
  (-portal-selection [_] nil)
  (-snapshot [_] (hub/snapshot h))
  (-tools [_] {})
  (-close! [_] (reset! closed? true)))

(deftest run-session-var-exists
  (is (fn? loop/run-session!)))

(deftest full-session-runs-runtime-and-publishes-assistant
  (let [h (hub/create-hub {:session-id "full-run-unit"})
        closed? (atom false)
        wb (->SessionWorkbench h closed?)
        agent-runtime
        (runtime/start
         {:agent/llm-client (llm.client/stub-client)
          :initial-state {:model "stub/v0"
                          :agent/system-message "Unit full run"}
          :exchange-chain
          (plugin/assemble-chain [(plugins.base/base-plugin)])}
         "full-run-unit")
        session-run (future
                      (loop/run-session! agent-runtime wb
                                         {:stdin-feeder? false}))]
    (hub/enqueue-human! h {:text "hello workbench" :refs []})
    (let [snapshot
          (loop [attempts 100]
            (let [snapshot (hub/snapshot h)]
              (if (or (and (= :waiting (:status snapshot))
                           (some #(= :assistant (:role %))
                                 (:turns snapshot)))
                      (zero? attempts))
                snapshot
                (do (Thread/sleep 10)
                    (recur (dec attempts))))))
          assistant-turns (filter #(= :assistant (:role %))
                                  (:turns snapshot))]
      (is (= 1 (count assistant-turns)))
      (is (str/includes? (:text (first assistant-turns))
                         "lateralus-v2 stub LLM echoed: hello workbench"))
      (is (= :waiting (:status snapshot))
          "the completed exchange returns to the long-session waiting state"))
    (hub/enqueue-human! h {:text "/quit" :refs []})
    (is (= :quit (deref session-run 2000 ::timeout)))
    (is (true? @closed?))))

(deftest guard-assistant-event-sanitizes-fake-cites
  (let [h (hub/create-hub {})
        _ (hub/put-ref! h {:id "real-id-1111-2222-3333-444444444444"
                           :label "x"
                           :preview "p"
                           :value 1})
        wb (reify proto/Workbench
             (-url [_] "")
             (-portal-url [_] nil)
             (-publish! [_ _])
             (-await-human! [_ _] {})
             (-attach-selection! [_] nil)
             (-submit-portal! [_ _ _] {})
             (-clear-portal! [_] {})
             (-snapshot [_] (hub/snapshot h))
             (-tools [_] {})
             (-close! [_] nil))
        event (loop/guard-assistant-event
               {:exchange/response "see @portal/2a9f41c3 in Portal"
                :agent/all-tool-results []}
               wb)]
    (is (true? (::loop/needs-repair? event)))
    (is (re-find #"invalid @portal cite" (:text event)))))

(deftest guard-assistant-event-ok-with-submit
  (let [h (hub/create-hub {})
        id "cfe479cc-5fba-4968-8db7-517ff6724195"
        _ (hub/put-ref! h {:id id :label "t" :preview "p" :value 1})
        wb (reify proto/Workbench
             (-url [_] "")
             (-portal-url [_] nil)
             (-publish! [_ _])
             (-await-human! [_ _] {})
             (-attach-selection! [_] nil)
             (-submit-portal! [_ _ _] {})
             (-clear-portal! [_] {})
             (-snapshot [_] (hub/snapshot h))
             (-tools [_] {})
             (-close! [_] nil))
        event (loop/guard-assistant-event
               {:exchange/response (str "table: @portal/" (subs id 0 8))
                :agent/all-tool-results
                [{:call {:function {:name "portal_submit"}}
                  :result (str "{\"ok\":true,\"cite\":\"@portal/" id
                               "\",\"ref\":{\"id\":\"" id "\"}}")}]}
               wb)]
    (is (false? (::loop/needs-repair? event)))
    (is (re-find (re-pattern id) (:text event)))))

(deftest portal-repair-preserves-original-turn-details-link
  (let [h (hub/create-hub {:session-id "repair-link"})
        wb (->SessionWorkbench h (atom false))
        calls (atom 0)
        original-id "11111111-1111-1111-1111-111111111111"
        repair-id "22222222-2222-2222-2222-222222222222"
        portal-id "33333333-3333-3333-3333-333333333333"
        agent-runtime
        (reify runtime/AgentRuntime
          (session-id [_] "repair-link")
          (send-message [_ _]
            (if (= 1 (swap! calls inc))
              {:exchange/response "Done: @portal/prompt"
               :agent/all-tool-results
               [{:call {:function {:name "tool_promote"}}
                 :result "{\"ok\":true}"}]
               :stream/turn-id original-id}
              {:exchange/response (str "Done: @portal/" portal-id)
               :agent/all-tool-results
               [{:call {:function {:name "portal_submit"}}
                 :result (str "{\"ok\":true,\"cite\":\"@portal/" portal-id
                              "\",\"ref\":{\"id\":\"" portal-id "\"}}")}]
               :stream/turn-id repair-id}))
          (stop [_] {}))
        repaired (#'loop/run-exchange! agent-runtime wb "promote it")
        guard-turn (->> (:turns (hub/snapshot h))
                        (filter #(= :system (:role %)))
                        last)]
    (is (= repair-id (:turn-id repaired)))
    (is (= original-id (:turn-id guard-turn))
        "the guard notice links to the lifecycle turn that triggered repair")
    (is (str/includes? (:text guard-turn) "Portal guard"))))

(deftest guard-surfaces-raised-http-errors
  (let [wb (reify proto/Workbench
             (-url [_] "")
             (-portal-url [_] nil)
             (-publish! [_ _])
             (-await-human! [_ _] {})
             (-attach-selection! [_] nil)
             (-submit-portal! [_ _ _] {})
             (-clear-portal! [_] {})
             (-snapshot [_] {})
             (-tools [_] {})
             (-close! [_] nil))
        ex (ex-info "LLM HTTP :http-error failed: 403"
                    {:kind :http-error :status 403
                     :body {:error "ollama cloud is disabled: remote model is unavailable"}})
        event (loop/guard-assistant-event
               {:exchange/response ""
                :error/raised {:exception ex :stage :llm}}
               wb)]
    (is (= :error (:role event)))
    (is (re-find #"ollama cloud is disabled" (:text event)))
    (is (re-find #"HTTP 403" (:text event)))))

(deftest friendly-exchange-error-maps-statuses
  (let [http-ex (fn [status msg]
                  (ex-info (str "LLM HTTP :http-error failed: " status)
                           {:kind :http-error
                            :status status
                            :body {:error {:message msg}}}))]
    (testing "429 explains throttling and mentions the retry"
      (let [text (loop/friendly-exchange-error (http-ex 429 "slow down") nil)]
        (is (str/includes? text "429"))
        (is (str/includes? text "slow down"))
        (is (str/includes? text "retried"))))
    (testing "401 points at the API key"
      (is (str/includes? (loop/friendly-exchange-error (http-ex 401 "bad key") nil)
                         "API key")))
    (testing "5xx says provider error, transient"
      (is (str/includes? (loop/friendly-exchange-error (http-ex 503 "overloaded") nil)
                         "503")))
    (testing "transport kind"
      (is (str/includes? (loop/friendly-exchange-error
                          (ex-info "connection refused" {:kind :transport}) nil)
                         "network error")))
    (testing "result map with :error/raised gets the same treatment"
      (let [result {:error/raised {:exception (http-ex 429 "quota")}}]
        (is (str/includes? (loop/friendly-exchange-error nil result) "429"))))))
