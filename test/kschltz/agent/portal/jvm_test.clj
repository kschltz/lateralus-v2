(ns kschltz.agent.portal.jvm-test
  "Tests for Portal JVM helpers that do not require djblue/portal on the classpath."
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.portal.jvm :as jvm]
            [kschltz.agent.portal.session :as session]
            [kschltz.agent.portal.protocol :as ui]))

(deftest reply-command-name-stable
  (is (= 'kschltz.agent.portal/reply! jvm/reply-command-name)))

(deftest start-without-open
  (let [ui (jvm/start! {:session-id "t" :open? false})]
    (try
      (is (satisfies? ui/AgentUi ui))
      (ui/publish! ui {:type :system :text "x"})
      (is (= 1 (count (:turns @(session/transcript-atom (:session ui))))))
      (finally
        (ui/close! ui)))))

(deftest available-pred-boolean
  (is (boolean? (jvm/available?))))
