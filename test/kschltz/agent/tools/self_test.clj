(ns kschltz.agent.tools.self-test
  "Tests for the self_status self-awareness tool."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.self :as tools.self]))

(defn- ctx
  "Build a minimal interceptor ctx for the self_status tool."
  ([state] (ctx state nil))
  ([state workspace-root]
   {:agent/state       state
    :exchange/session-id "s-1"
    :embedder          (with-meta {} {:embedder/method :noop})
    :memory/backend    (with-meta {} {:memory-backend/impl :noop})}))

(deftest self-awareness-registry-contains-inspection-tools
  (testing "self-awareness-registry returns status and runtime description tools"
    (let [registry (tools.self/self-awareness-registry)]
      (is (= 2 (count registry)))
      (is (contains? registry "self_status"))
      (is (contains? registry "runtime_describe"))
      (is (every? tool/tool? (vals registry))))))

(deftest self-status-returns-json-string
  (testing "self_status returns a JSON string with the expected keys"
    (let [state {:model "test-model"
                 :base-url "http://test"
                 :agent/session-id "s-1"
                 :agent/last-request-messages [{:role "system"}]
                 :agent/token-usage {:prompt_tokens 10
                                     :completion_tokens 5
                                     :total_tokens 15}}
          t      (get (tools.self/self-awareness-registry) "self_status")
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
      (is (= 15 (get-in parsed [:tokens-used :total_tokens])))
      (is (int? (get-in parsed [:jdk :feature])))
      (is (boolean? (get-in parsed [:jdk :clerk-graaljs-safe?]))))))

(deftest self-status-uses-workspace-root
  (testing "self_status reports the configured workspace-root"
    (let [t      (get (tools.self/self-awareness-registry "/tmp/ws") "self_status")
          result (json/parse-string (tool/invoke-tool t {} (ctx {})) true)]
      (is (= "/tmp/ws" (get-in result [:location :workspace-root]))))))

(deftest self-status-defaults-token-usage
  (testing "self_status reports zero token usage when none has been accumulated"
    (let [t      (get (tools.self/self-awareness-registry) "self_status")
          result (json/parse-string (tool/invoke-tool t {} (ctx {})) true)]
      (is (= 0 (get-in result [:tokens-used :prompt_tokens])))
      (is (= 0 (get-in result [:tokens-used :completion_tokens])))
      (is (= 0 (get-in result [:tokens-used :total_tokens]))))))

(deftest self-status-input-validation
  (testing "self_status rejects unexpected arguments"
    (let [t      (get (tools.self/self-awareness-registry) "self_status")
          result (tool/invoke-tool t {:extra "bad"} (ctx {}))]
      (is (string? result))
      (is (str/includes? result "input validation failed"))
      (is (str/includes? result "self_status"))
      (is (str/includes? result ":extra")))))

(deftest runtime-describe-is-complete-and-redacted
  (testing "runtime_describe reports tool and chain contracts without secrets"
    (let [registry (tools.self/self-awareness-registry "/workspace")
          chain [{:name :kschltz.agent.test/guard
                  :plugin/name :base
                  :plugin/slot :guard
                  :enter identity}
                 {:name :kschltz.agent.test/tools
                  :plugin/name :tools
                  :plugin/slot :tools
                  :enter identity
                  :leave identity}]
          c (assoc (ctx {:model "test-model"
                         :base-url "https://example.test/v1"
                         :api-key "super-secret"
                         :agent/system-message "everything is an interceptor"
                         :agent/history [{:role "user"}]
                         :mcp/servers {"demo" {}}}
                        "/workspace")
                   :agent/agent-map {:agent/loop-opts {:max-loop-depth 7}}
                   :agent/loop-opts {:max-loop-depth 7}
                   :agent/tool-registry registry
                   :agent/exchange-chain chain)
          result (tool/invoke-tool (get registry "runtime_describe")
                                   {:section "all"}
                                   c)
          parsed (json/parse-string result true)]
      (is (true? (get-in parsed [:summary :configuration :api-key-set])))
      (is (not (str/includes? result "super-secret")))
      (is (= 7 (get-in parsed [:summary :loop-policy :max-loop-depth])))
      (is (= ["demo"] (get-in parsed [:summary :mcp-server-ids])))
      (is (= ["runtime_describe" "self_status"]
             (mapv :name (:tools parsed))))
      (is (= ["kschltz.agent.test/guard" "kschltz.agent.test/tools"]
             (mapv :name (:chain parsed))))
      (is (= ["enter" "leave"] (get-in parsed [:chain 1 :stages]))))))

(deftest runtime-describe-sections-and-input-validation
  (let [registry (tools.self/self-awareness-registry)
        t (get registry "runtime_describe")
        c (assoc (ctx {})
                 :agent/tool-registry registry
                 :agent/exchange-chain [])]
    (testing "section selects a bounded portion"
      (let [parsed (json/parse-string
                    (tool/invoke-tool t {:section "summary"} c)
                    true)]
        (is (contains? parsed :summary))
        (is (not (contains? parsed :tools)))
        (is (not (contains? parsed :chain)))))
    (testing "playbook section is self-update guidance"
      (let [parsed (json/parse-string
                    (tool/invoke-tool t {:section "playbook"} c)
                    true)]
        (is (contains? parsed :playbook))
        (is (string? (get-in parsed [:playbook :use-file-then-reload])))))
    (testing "unknown sections fail Malli validation"
      (let [result (tool/invoke-tool t {:section "secrets"} c)]
        (is (str/includes? result "input validation failed"))
        (is (str/includes? result "runtime_describe"))))))
