(ns kschltz.agent.portal.loop-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.portal.loop :as ploop]
            [kschltz.agent.portal.session :as session]
            [kschltz.agent.portal.jvm :as jvm]
            [kschltz.agent.runtime :as runtime]))

(defrecord FakeRuntime [calls]
  runtime/AgentRuntime
  (session-id [_] "sid")
  (send-message [_ text]
    (swap! calls conj text)
    {:exchange/response (str "echo:" text)
     :exchange/thinking "because"})
  (stop [_] {}))

(defn- wait-waiting!
  [s]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (when-not (= :waiting (:status @(session/transcript-atom s)))
        (when (> (System/currentTimeMillis) deadline)
          (throw (ex-info "session never entered :waiting" {})))
        (Thread/sleep 10)
        (recur)))))

(deftest run-session-quit
  (let [s (session/create-session {:session-id "loop"})
        calls (atom [])
        rt (->FakeRuntime calls)
        fut (future (ploop/run-session! rt s {:stdin-feeder? false}))]
    (try
      (wait-waiting! s)
      (jvm/reply! {:text "/quit"})
      (is (= :quit (deref fut 2000 :timeout)))
      (is (empty? @calls))
      (finally
        (when-not (realized? fut)
          (jvm/reply! {:text "/quit"})
          (deref fut 1000 :timeout))))))

(deftest run-session-one-turn
  (let [s (session/create-session {:session-id "loop2"})
        calls (atom [])
        rt (->FakeRuntime calls)
        fut (future (ploop/run-session! rt s {:stdin-feeder? false}))]
    (try
      (wait-waiting! s)
      (jvm/reply! {:text "hi"})
      (wait-waiting! s)
      (jvm/reply! {:text "/quit"})
      (is (= :quit (deref fut 2000 :timeout)))
      (is (= ["hi"] @calls))
      (let [turns (:turns @(session/transcript-atom s))
            asst (first (filter #(= :assistant (:role %)) turns))]
        (is (= "echo:hi" (:text asst)))
        (is (= "because" (:thinking asst))))
      (finally
        (when-not (realized? fut)
          (jvm/reply! {:text "/quit"})
          (deref fut 1000 :timeout))))))
