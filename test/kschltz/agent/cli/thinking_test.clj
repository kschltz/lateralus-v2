(ns kschltz.agent.cli.thinking-test
  "Tests for the optional thinking/reasoning display pack."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [malli.core :as m]
            [kschltz.agent.cli.thinking :as thinking]
            [kschltz.agent.system :as system])
  (:import [java.io File]))

(deftest thinking-config-schema
  (is (m/validate thinking/ThinkingConfig {}))
  (is (m/validate thinking/ThinkingConfig {:mode :off}))
  (is (m/validate thinking/ThinkingConfig {:mode :preview :preview-chars 80}))
  (is (m/validate thinking/ThinkingConfig {:mode :full}))
  (is (m/validate thinking/ThinkingConfig {:mode :log :log-dir "tmp" :log-file "/tmp/t.txt"}))
  (is (not (m/validate thinking/ThinkingConfig {:mode :verbose})))
  (is (not (m/validate thinking/ThinkingConfig {:preview-chars 0}))))

(deftest validate-rejects-invalid
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid :lateralus/thinking"
                        (thinking/validate! {:mode :nope}))))

(deftest normalize-defaults-to-off
  (is (= :off (:mode (thinking/normalize nil))))
  (is (= :off (:mode (thinking/normalize {}))))
  (is (= 240 (:preview-chars (thinking/normalize {})))))

(deftest format-block-modes
  (testing "off and log produce no display block"
    (is (nil? (thinking/format-block {:mode :off} "reason")))
    (is (nil? (thinking/format-block {:mode :log} "reason"))))
  (testing "full includes entire text"
    (let [block (thinking/format-block {:mode :full} "step one\nstep two")]
      (is (str/includes? block "[thinking]"))
      (is (str/includes? block "step one\nstep two"))))
  (testing "preview truncates"
    (let [block (thinking/format-block {:mode :preview :preview-chars 10}
                                       "abcdefghijklmnop")]
      (is (str/includes? block "[thinking]"))
      (is (str/includes? block "abcdefghij…"))
      (is (not (str/includes? block "klmnop")))))
  (testing "blank thinking yields nil"
    (is (nil? (thinking/format-block {:mode :full} "")))
    (is (nil? (thinking/format-block {:mode :full} "   ")))))

(deftest apply-thinking-log-writes-file
  (let [tmp (File/createTempFile "lateralus-thinking-" ".txt")
        path (.getAbsolutePath tmp)]
    (.delete tmp)
    (try
      (is (nil? (thinking/apply-thinking!
                 {:mode :log :log-file path}
                 {:thinking "because reasons"
                  :session-id "s1"
                  :user-text "hi"})))
      (let [body (slurp path)]
        (is (str/includes? body "because reasons"))
        (is (str/includes? body "session=s1"))
        (is (str/includes? body ";;; user: hi")))
      (finally
        (.delete (io/file path))))))

(deftest apply-thinking-off-is-silent
  (is (nil? (thinking/apply-thinking! {:mode :off}
                                      {:thinking "secret"}))))

(deftest from-agent-reads-config
  (is (= :preview (:mode (thinking/from-agent
                          {:agent/thinking {:mode :preview}}))))
  (is (= :off (:mode (thinking/from-agent {})))))

(deftest system-wires-thinking-key
  (let [sys (ig/init (assoc system/default-config
                            :lateralus/thinking {:mode :preview :preview-chars 50}))
        agent (:lateralus/agent sys)]
    (try
      (is (= :preview (get-in agent [:agent/thinking :mode])))
      (is (= 50 (get-in agent [:agent/thinking :preview-chars])))
      (finally (ig/halt! sys)))))

(deftest system-rejects-bad-thinking-config
  (is (thrown? Exception
               (ig/init (assoc system/default-config
                               :lateralus/thinking {:mode :wat})))))
