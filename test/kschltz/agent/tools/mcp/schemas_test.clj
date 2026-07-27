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

(deftest http-stanza-validates
  (testing "Streamable HTTP stanzas validate"
    (is (schemas/valid-config?
         {:servers
          {"acme"
           {:transport :http
            :url "https://mcp.example.com/mcp"
            :bearer-token-env "ACME_TOKEN"
            :headers {"X-Tenant" "1"}}}}))
    (is (schemas/valid-config?
         {:servers
          {"local"
           {:url "http://127.0.0.1:8080/mcp"
            :allow-http? true
            :allow-loopback? true}}}))
    (is (= :http (schemas/server-transport {:url "https://x/mcp"})))
    (is (= :stdio (schemas/server-transport {:command "npx"})))))

(deftest invalid-config-rejected
  (testing "missing command/url and bad types fail"
    (is (not (schemas/valid-config? {:servers {"x" {}}})))
    (is (not (schemas/valid-config? {:servers {"x" {:command 1}}})))
    (is (not (schemas/valid-config?
              {:servers {"x" {:command "npx" :env {"A" 1}}}})))
    (is (not (schemas/valid-config?
              {:servers {"x" {:transport :http}}})))))

(deftest dynamic-policy-validates
  (is (schemas/valid-config? {:servers {} :dynamic {:enabled? true}}))
  (is (schemas/valid-config? {:servers {} :dynamic {:enabled? false}}))
  (is (not (schemas/valid-config? {:dynamic {:enabled? "yes"}}))))

