(ns kschltz.agent.workbench.loop-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.workbench.hub :as hub]
            [kschltz.agent.workbench.loop :as loop]
            [kschltz.agent.workbench.protocol :as proto]))

(deftest run-session-var-exists
  (is (fn? loop/run-session!)))

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
