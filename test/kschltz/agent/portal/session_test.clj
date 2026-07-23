(ns kschltz.agent.portal.session-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.portal.protocol :as ui]
            [kschltz.agent.portal.session :as session]
            [kschltz.agent.portal.jvm :as jvm]))

(defn- wait-waiting!
  [s]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (when-not (= :waiting (:status @(session/transcript-atom s)))
        (when (> (System/currentTimeMillis) deadline)
          (throw (ex-info "session never entered :waiting" {})))
        (Thread/sleep 10)
        (recur)))))

(deftest publish-and-await-roundtrip
  (let [s (session/create-session {:session-id "s1"})]
    (try
      (ui/publish! s {:type :system :text "hello"})
      (let [f (future (ui/await-human! s {}))]
        (wait-waiting! s)
        (is (= {:ok true :queued true}
               (jvm/reply! {:text "  ping  "})))
        (is (= "ping" (deref f 2000 :timeout)))
        (let [turns (:turns @(session/transcript-atom s))]
          (is (= :system (:role (first turns))))
          (is (= :user (:role (last turns))))
          (is (= "ping" (:text (last turns)))))
        (is (= :running (:status @(session/transcript-atom s)))))
      (finally
        (ui/close! s)
        (is (= :closed (:status @(session/transcript-atom s))))))))

(deftest reply-without-session
  (session/clear-inbox-binding!)
  (is (= {:ok false :reason :no-active-session}
         (jvm/reply! {:text "x"}))))

(deftest reply-blank
  (let [s (session/create-session {})]
    (try
      (session/bind-inbox! (:inbox s))
      (is (= {:ok false :reason :blank}
             (jvm/reply! {:text "  "})))
      (finally
        (ui/close! s)))))

(deftest await-timeout
  (let [s (session/create-session {:await-ms 50})]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"timed out"
                            (ui/await-human! s {:timeout-ms 50})))
      (finally
        (ui/close! s)))))
