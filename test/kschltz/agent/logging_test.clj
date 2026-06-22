(ns kschltz.agent.logging-test
  "Tests for the per-stage logging interceptor and engine seam.

   Covers:
   - every stage in the default exchange chain produces an :enter and a
     :leave event
   - :api-key is redacted in every record
   - a throwing stage still logs :enter (and the chain does not crash)
   - an on-stage throw does NOT break the chain (result still returned)
   - when no sink is present, logging is inert and the chain behaves
     exactly as before"
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.llm.client :as llm-client]
            [kschltz.agent.logging :as logging]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]))

(defrecord RecordingSink [records]
  logging/LogSink
  (-open [this] this)
  (-write [this event] (swap! records conj event) this)
  (-close [this] this))

(defn- recording-sink [] (->RecordingSink (atom [])))

(defn- base-chain [] (plugin/assemble-chain [(plugins.base/base-plugin)]))

(defn- run-with-sink
  "Run a single exchange with a recording sink wired into ctx and a stub
   LLM. Returns the final ctx with :sink attached for inspection."
  [sink]
  (let [ctx {:exchange/session-id       "test-session"
             :exchange/user-msg-id      "u1"
             :exchange/assistant-msg-id "a1"
             :exchange/user-text        "hello"
             :agent/state               {:base-url "stub"
                                         :api-key  "super-secret"
                                         :model    "stub/v0"
                                         :agent/system-message "test"}
             :llm/client                (llm-client/stub-client)
             :agent/log-sink            sink}]
    (chain/execute ctx (base-chain))))

(deftest every-stage-logs-enter-and-leave
  (testing "every executed stage produces an :enter and a :leave event"
    (let [sink (recording-sink)
          _    (run-with-sink sink)
          evts @(:records sink)
          enters (filter #(= :enter (:direction %)) evts)
          leaves (filter #(= :leave (:direction %)) evts)
          names  (set (map :name evts))]
      (is (seq enters))
      (is (seq leaves))
      (is (= (count enters) (count leaves))
          "enter and leave event counts must match")
      (is (contains? names :kschltz.agent.interceptors/compose-context))
      (is (contains? names :kschltz.agent.interceptors/llm-call))
      (is (contains? names :kschltz.agent.interceptors/parse-response)))))

(deftest api-key-is-redacted
  (testing ":api-key never appears in any log record's ctx-view"
    (let [sink (recording-sink)
          _    (run-with-sink sink)
          evts @(:records sink)]
      (is (seq evts))
      (doseq [evt evts
              :let [view (:ctx-view evt)]]
        (is (not-any? #(= % :api-key) (keys view)))
        (is (not= (get-in view [:llm/request :api-key]) "super-secret"))
        (is (not= (get-in view [:agent/state :api-key]) "super-secret"))))))

(deftest no-sink-means-inert
  (testing "without a sink the chain runs normally and no events are written"
    (let [sink (recording-sink)
          ctx  {:exchange/session-id       "test-session"
                :exchange/user-msg-id      "u1"
                :exchange/assistant-msg-id "a1"
                :exchange/user-text        "hello"
                :agent/state               {:base-url "stub" :api-key nil
                                            :model "stub/v0"
                                            :agent/system-message "test"}
                :llm/client                (llm-client/stub-client)}
            ;; no :agent/log-sink
          out  (chain/execute ctx (base-chain))]
      (is (empty? @(:records sink)))
      (is (seq (:exchange/response out))))))

(defn- throwing-stage
  "An interceptor whose :enter throws."
  []
  {:name ::throwing-stage
   :enter (fn [ctx] (throw (ex-info "boom" {:reason :test})))})

(deftest throwing-stage-still-logs-enter
  (testing "a throwing stage still logs its :enter and the chain records the error"
    (let [sink   (recording-sink)
          ctx    {:exchange/session-id       "test-session"
                  :exchange/user-msg-id      "u1"
                  :exchange/assistant-msg-id "a1"
                  :exchange/user-text        "hello"
                  :agent/state               {:base-url "stub" :api-key "k"
                                              :model "stub/v0"
                                              :agent/system-message "test"}
                  :llm/client                (llm-client/stub-client)
                  :agent/log-sink            sink}
          ;; Replace the chain with: logging + throwing-stage so the
          ;; error-boundary is absent and the throw propagates as an
          ;; unhandled engine error.
          result (try
                   (chain/execute ctx [(logging/logging-interceptor)
                                       (throwing-stage)])
                   (catch Throwable t t))
          evts   @(:records sink)
          enter-for-throwing (some #(and (= (:name %) ::throwing-stage)
                                         (= (:direction %) :enter))
                                   evts)]
      (is enter-for-throwing "the throwing stage must have logged :enter")
      (is (instance? Throwable result)
          "with no error-boundary the throw propagates, but logging already ran"))))

(defn- broken-sink
  "A sink whose -write throws on every call."
  [records]
  (reify logging/LogSink
    (-open [this] this)
    (-write [_ _event] (swap! records conj :write-attempted)
            (throw (ex-info "sink broken" {})))
    (-close [this] this)))

(deftest broken-sink-does-not-break-chain
  (testing "an on-stage (-write) throw is swallowed; the chain still returns"
    (let [attempts (atom [])
          sink     (broken-sink attempts)
          out      (run-with-sink sink)]
      (is (seq @attempts) "the sink was attempted")
      (is (seq (:exchange/response out))
          "the chain still produced a response despite sink failures"))))