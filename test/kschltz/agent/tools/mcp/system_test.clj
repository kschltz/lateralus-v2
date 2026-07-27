(ns kschltz.agent.tools.mcp.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [kschltz.agent.system :as system]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.mcp.protocol :as proto]
            [kschltz.agent.tools.mcp.test-util :as tu]
            [kschltz.agent.tools.mcp.tools :as tools]))

(deftest assert-key-rejects-bad-config
  (is (thrown? Exception
               (ig/init (assoc system/default-config
                               :lateralus/mcp-tools
                               {:servers {"x" {}}})))))

(deftest default-config-includes-empty-mcp
  (is (= {:servers {} :dynamic {:enabled? false}}
         (:lateralus/mcp-tools system/default-config)))
  (let [sys (ig/init system/default-config)]
    (try
      (is (proto/mcp-session? (:lateralus/mcp-tools sys)))
      (is (empty? (proto/-registry (:lateralus/mcp-tools sys))))
      (let [reg (:lateralus/tool-registry sys)
            baseline-keys ["file_read" "self_status" "web_search" "mcp_list_servers"]]
        (doseq [k baseline-keys]
          (is (contains? reg k)
              (str "baseline tool missing: " k))))
      (finally
        (ig/halt! sys)))))

(deftest loop-integration-mcp-tool-call
  (testing "scripted stub LLM can invoke an adapted MCP tool through the registry"
    (let [c (tu/fake-loopback-client)
          registry (tools/mcp-registry
                    {:servers {"fake" {:command "unused" :initialized? true}}
                     :clients {"fake" c}})
          calls [{:function {:name "fake_echo"
                             :arguments "{\"message\":\"via-loop\"}"}}]
          results (tool/execute-tools registry {} calls)]
      (is (= 1 (count results)))
      (is (re-find #"via-loop" (:result (first results))))
      (tools/halt-registry! registry))))
