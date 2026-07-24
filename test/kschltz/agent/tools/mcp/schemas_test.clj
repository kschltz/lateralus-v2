(ns kschltz.agent.tools.mcp.schemas-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [kschltz.agent.tools.mcp.schemas :as schemas]))

(deftest empty-config-validates
  (testing "air-gap defaults validate"
    (is (schemas/valid-config? {}))
    (is (schemas/valid-config? {:servers {}}))
    (is (schemas/valid-config? {:enabled? false}))
    (is (schemas/valid-config? {:enabled? true :servers {}}))))

(deftest server-stanza-validates
  (testing "Claude Desktop-like stanzas validate"
    (is (schemas/valid-config?
         {:servers
          {"filesystem"
           {:command "npx"
            :args ["-y" "@modelcontextprotocol/server-filesystem" "/tmp"]
            :env {"FOO" "bar"}}}}))
    (is (schemas/valid-config?
         {:servers
          {:github {:command "npx"
                    :args ["-y" "@modelcontextprotocol/server-github"]}}}))))

(deftest invalid-config-rejected
  (testing "missing command and bad types fail"
    (is (not (schemas/valid-config? {:servers {"x" {}}})))
    (is (not (schemas/valid-config? {:servers {"x" {:command 1}}})))
    (is (not (schemas/valid-config?
              {:servers {"x" {:command "npx" :env {"A" 1}}}})))))

(deftest tool-descriptor-schema
  (is (m/validate schemas/ToolDescriptor
                  {:name "echo" :description "d" :inputSchema {:type "object"}}))
  (is (not (m/validate schemas/ToolDescriptor {:description "no name"}))))
