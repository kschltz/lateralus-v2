(ns kschltz.agent.tools.mcp.tools-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [kschltz.agent.system :as system]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.mcp.protocol :as proto]
            [kschltz.agent.tools.mcp.test-util :as tu]
            [kschltz.agent.tools.mcp.tools :as tools]))

(deftest empty-registry-airgap
  (testing "empty/disabled configs spawn nothing"
    (let [r (tools/mcp-registry {:servers {}})]
      (is (= {} r))
      (is (= [] (:mcp/clients (meta r)))))
    (let [r (tools/mcp-registry {:enabled? false
                                 :servers {"x" {:command "npx"}}})]
      (is (= {} r)))))

(deftest injected-client-registry
  (let [c (tu/fake-loopback-client)
        r (tools/mcp-registry
           {:servers {"fake" {:command "unused" :initialized? true}}
            :clients {"fake" c}})]
    (is (contains? r "fake_echo"))
    (is (contains? r "fake_add"))
    (doseq [[n t] r]
      (is (tool/portable-tool-name? n))
      (is (tool/tool? t)))
    (let [out (json/parse-string
               (tool/-invoke (get r "fake_echo") {:message "ping"} {})
               true)]
      (is (= "ping" (:content out))))
    (tools/halt-registry! r)
    (tools/halt-registry! r)))

(deftest native-image-refuses-servers
  (is (thrown-with-msg?
       Exception #"native-image"
       (tools/mcp-registry
        {:native-image? true
         :servers {"x" {:command "npx"}}}))))

(deftest system-init-halt-empty
  (let [cfg (assoc system/default-config
                   :lateralus/mcp-tools {:servers {}})
        sys (ig/init cfg)]
    (try
      (is (map? (:lateralus/mcp-tools sys)))
      (is (empty? (:lateralus/mcp-tools sys)))
      (finally
        (ig/halt! sys)))))

(deftest system-init-halt-with-injected-client
  (let [c (tu/fake-loopback-client)
        cfg (-> system/default-config
                (assoc :lateralus/mcp-tools
                       {:servers {"fake" {:command "unused" :initialized? true}}
                        :clients {"fake" c}}))
        sys (ig/init cfg)]
    (try
      (let [reg (:lateralus/tool-registry sys)]
        (is (contains? reg "fake_echo"))
        (is (contains? reg "file_read")
            "non-MCP tools still present when MCP is wired"))
      (finally
        (ig/halt! sys)
        (is (true? (:closed? (proto/-server-info c))))))))

(deftest disabled-ignores-servers
  (let [r (tools/mcp-registry
           {:enabled? false
            :servers {"fake" {:command "should-not-run"}}})]
    (is (= {} r))))
