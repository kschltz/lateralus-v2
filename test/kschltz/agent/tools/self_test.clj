(ns kschltz.agent.tools.self-test
  "Tests for the self/status self-awareness tool."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.self :as tools.self]))

(defn- ctx
  "Build a minimal interceptor ctx for the self/status tool."
  ([state] (ctx state nil))
  ([state workspace-root]
   {:agent/state       state
    :exchange/session-id "s-1"
    :embedder          (with-meta {} {:embedder/method :noop})
    :memory/backend    (with-meta {} {:memory-backend/impl :noop})}))

(deftest self-awareness-registry-contains-one-tool
  (testing "self-awareness-registry returns the self/status tool"
    (let [registry (tools.self/self-awareness-registry)]
      (is (= 1 (count registry)))
      (is (contains? registry "self/status"))
      (is (tool/tool? (get registry "self/status"))))))

(deftest self-status-returns-json-string
  (testing "self/status returns a JSON string with the expected keys"
    (let [state {:model "test-model"
                 :base-url "http://test"
                 :agent/session-id "s-1"
                 :agent/last-request-messages [{:role "system"}]
                 :agent/token-usage {:prompt_tokens 10
                                     :completion_tokens 5
                                     :total_tokens 15}}
          t      (get (tools.self/self-awareness-registry) "self/status")
          result (tool/invoke-tool t {} (ctx state))
          parsed (json/parse-string result true)]
      (is (string? result))
      (is (str/includes? result "\"time\""))
      (is (= "UTC" (:timezone parsed)))
      (is (= "test-model" (get-in parsed [:configuration :model])))
      (is (= "http://test" (get-in parsed [:configuration :base-url])))
      (is (= "s-1" (get-in parsed [:configuration :session-id])))
      (is (= "noop" (get-in parsed [:configuration :embedder])))
      (is (= "noop" (get-in parsed [:configuration :memory])))
      (is (string? (get-in parsed [:location :cwd])))
      (is (= "unset" (get-in parsed [:location :workspace-root])))
      (is (= 1 (get-in parsed [:context :message-count])))
      (is (= 10 (get-in parsed [:tokens-used :prompt_tokens])))
      (is (= 5 (get-in parsed [:tokens-used :completion_tokens])))
      (is (= 15 (get-in parsed [:tokens-used :total_tokens]))))))

(deftest self-status-uses-workspace-root
  (testing "self/status reports the configured workspace-root"
    (let [t      (get (tools.self/self-awareness-registry "/tmp/ws") "self/status")
          result (json/parse-string (tool/invoke-tool t {} (ctx {})) true)]
      (is (= "/tmp/ws" (get-in result [:location :workspace-root]))))))

(deftest self-status-defaults-token-usage
  (testing "self/status reports zero token usage when none has been accumulated"
    (let [t      (get (tools.self/self-awareness-registry) "self/status")
          result (json/parse-string (tool/invoke-tool t {} (ctx {})) true)]
      (is (= 0 (get-in result [:tokens-used :prompt_tokens])))
      (is (= 0 (get-in result [:tokens-used :completion_tokens])))
      (is (= 0 (get-in result [:tokens-used :total_tokens]))))))

(deftest self-status-input-validation
  (testing "self/status rejects unexpected arguments"
    (let [t      (get (tools.self/self-awareness-registry) "self/status")
          result (tool/invoke-tool t {:extra "bad"} (ctx {}))]
      (is (string? result))
      (is (str/includes? result "input validation failed"))
      (is (str/includes? result "self/status"))
      (is (str/includes? result ":extra")))))
