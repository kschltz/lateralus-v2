(ns kschltz.agent.loop.rescue-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.loop.rescue :as rescue]))

(def ^:private registry
  {"spotify_api" {} "echo_probe" {}})

(deftest extracts-registered-pseudo-calls
  (let [text (str "Let me test: spotify_api({\"path\": \"/v1/search\","
                  " \"query\": {\"q\": \"Coltrane\"}}) then done.")
        calls (rescue/pseudo-calls registry text)]
    (is (= 1 (count calls)))
    (is (= "spotify_api" (get-in calls [0 :function :name])))
    (is (str/includes? (get-in calls [0 :function :arguments]) "/v1/search"))))

(deftest ignores-unknown-tool-prose
  (testing "prose about unregistered tools does not dispatch"
    (is (empty? (rescue/pseudo-calls registry "a user mentioned foo({...}) in prose")))
    (is (empty? (rescue/pseudo-calls registry "call spotify_api for me please — no args given")))))

(deftest handles-blank-and-nil
  (is (empty? (rescue/pseudo-calls registry nil)))
  (is (empty? (rescue/pseudo-calls registry "")))
  (is (empty? (rescue/pseudo-calls {} "spotify_api({\"x\": 1})"))))

(deftest scans-nested-and-multiple
  (let [calls (rescue/pseudo-calls
               registry
               "first echo_probe({\"x\": 1}) then spotify_api({\"path\": \"/v1/play\", \"body\": {\"a\": 1}})")]
    (is (= ["echo_probe" "spotify_api"] (mapv #(get-in % [:function :name]) calls)))))

(deftest malformed-args-ignored
  (is (empty? (rescue/pseudo-calls registry "spotify_api({not json))")))
  (is (empty? (rescue/pseudo-calls registry "echo_probe("))))
