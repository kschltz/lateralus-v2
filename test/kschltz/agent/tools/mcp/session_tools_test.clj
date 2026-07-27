(ns kschltz.agent.tools.mcp.session-tools-test
  "Tests for MCP session control-tool registry wiring."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.mcp.protocol :as proto]
            [kschltz.agent.tools.mcp.session :as session]
            [kschltz.agent.tools.mcp.session-tools :as session-tools]
            [kschltz.agent.tools.mcp.test-util :as tu]))

(deftest session-tools-registry-empty-without-session
  (is (= {} (session-tools/session-tools-registry nil)))
  (is (= {} (session-tools/session-tools-registry {}))))

(deftest session-tools-registry-exposes-four-tools
  (let [s (session/mcp-session {:servers {} :dynamic {:enabled? true}})
        reg (session-tools/session-tools-registry s)]
    (try
      (is (= #{"mcp_list_servers" "mcp_upsert_server"
               "mcp_remove_server" "mcp_refresh_server"}
             (set (keys reg))))
      (is (every? tool/tool? (vals reg)))
      (finally
        (proto/halt-session! s)))))

(deftest list-servers-read-only
  (let [s (session/mcp-session {:servers {} :dynamic {:enabled? false}})
        t (get (session-tools/session-tools-registry s) "mcp_list_servers")
        parsed (json/parse-string (tool/-invoke t {} {}) true)]
    (try
      (is (true? (:ok parsed)))
      (is (false? (:dynamic-enabled? parsed)))
      (is (zero? (:tool-count parsed)))
      (finally
        (proto/halt-session! s)))))

(deftest upsert-tool-respects-dynamic-gate
  (testing "disabled dynamic policy returns model-visible error envelope"
    (let [s (session/mcp-session {:servers {} :dynamic {:enabled? false}})
          t (get (session-tools/session-tools-registry s) "mcp_upsert_server")
          parsed (json/parse-string
                  (tool/-invoke t
                                {:server-id "x"
                                 :config {:command "npx" :args ["-y" "noop"]}}
                                {})
                  true)]
      (try
        (is (false? (:ok parsed)))
        (is (= "disabled" (:phase parsed)))
        (is (= "mcp_upsert_server" (:tool parsed)))
        (finally
          (proto/halt-session! s)))))
  (testing "enabled dynamic policy upserts via injected client"
    (let [c (tu/fake-loopback-client "dyn")
          s (session/mcp-session {:servers {} :dynamic {:enabled? true}})
          ;; Control tool validates ServerConfig (no :__client). Exercise
          ;; success path via session + remove tool transition envelope.
          tools (session-tools/session-tools-registry s)]
      (try
        (proto/-upsert-server! s "dyn"
                               {:command "unused" :initialized? true :__client c}
                               {})
        (let [parsed (json/parse-string
                      (tool/-invoke (get tools "mcp_remove_server")
                                    {:server-id "dyn"} {})
                      true)]
          (is (true? (:ok parsed)))
          (is (= "mcp-remove-server"
                 (name (keyword (get-in parsed [:transition :op]))))))
        (finally
          (proto/halt-session! s))))))
