(ns kschltz.agent.tools.mcp.session-test
  "Unit tests for McpSession + control tools + mid-exchange visibility."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fake-mcp-http-server :as fake-http]
            [kschltz.agent.llm.client :as llm-client]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.plugins.tools :as plugins.tools]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.mcp.protocol :as proto]
            [kschltz.agent.tools.mcp.session :as session]
            [kschltz.agent.tools.mcp.session-tools :as session-tools]
            [kschltz.agent.tools.mcp.test-util :as tu]
            [kschltz.agent.transitions.interceptors :as tr.ix]))

(defn- dynamic-session
  []
  (session/mcp-session {:servers {}
                        :dynamic {:enabled? true}}))

(defn- seeded-session
  []
  (let [c (tu/fake-loopback-client "fake")]
    (session/mcp-session
     {:servers {"fake" {:command "unused" :initialized? true}}
      :clients {"fake" c}
      :dynamic {:enabled? true}})))

(defn- tool-names-in-request
  [req]
  (->> (:tools req)
       (map (fn [tdef] (get-in tdef [:function :name])))
       (remove nil?)
       set))

(deftest session-boot-airgap
  (let [s (session/mcp-session {:servers {}})]
    (is (proto/mcp-session? s))
    (is (= {} (proto/-registry s)))
    (is (false? (proto/-dynamic-enabled? s)))
    (proto/halt-session! s)))

(deftest session-boot-injected-client
  (let [s (seeded-session)
        reg (proto/-registry s)
        st (proto/-status s)]
    (try
      (is (contains? reg "fake_echo"))
      (is (= 1 (count (:servers st))))
      (is (true? (:dynamic-enabled? st)))
      (finally
        (proto/halt-session! s)))))

(deftest upsert-requires-dynamic-flag
  (let [s (session/mcp-session {:servers {} :dynamic {:enabled? false}})
        tools (session-tools/session-tools-registry s)
        t (get tools "mcp_upsert_server")
        parsed (json/parse-string
                (tool/-invoke t
                              {:server-id "dyn"
                               :config {:command "npx" :args ["-y" "x"]}}
                              {:agent/tool-registry tools})
                true)]
    (is (false? (:ok parsed)))
    (is (= "disabled" (:phase parsed)))
    (proto/halt-session! s)))

(deftest upsert-remove-refresh-via-session
  (let [s (dynamic-session)
        c (tu/fake-loopback-client "dyn")]
    (try
      (let [status (proto/-upsert-server!
                    s "dyn"
                    {:command "unused" :initialized? true :__client c}
                    {:reserved-names #{"file_read"}})]
        (is (= "dyn" (:server-id status)))
        (is (some #{"dyn_echo"} (:tools status)))
        (is (contains? (proto/-registry s) "dyn_echo")))
      (let [refreshed (proto/-refresh-server! s "dyn")]
        (is (contains? (set (:tools refreshed)) "dyn_echo")))
      (let [removed (proto/-remove-server! s "dyn")]
        (is (true? (:removed removed)))
        (is (not (contains? (proto/-registry s) "dyn_echo"))))
      (finally
        (proto/halt-session! s)))))

(deftest upsert-collision-with-reserved-names
  (let [s (dynamic-session)
        c (tu/fake-loopback-client "fake")]
    (try
      (is (thrown-with-msg?
           Exception #"collision"
           (proto/-upsert-server!
            s "fake"
            {:command "unused" :initialized? true :__client c}
            {:reserved-names #{"fake_echo"}})))
      (is (= {} (proto/-registry s)))
      (finally
        (proto/halt-session! s)
        (proto/-close-client! c)))))

(deftest control-tools-list-remove-transitions
  (let [s (dynamic-session)
        c (tu/fake-loopback-client "x")
        tools (session-tools/session-tools-registry s)]
    (try
      (proto/-upsert-server! s "x"
                             {:command "unused" :initialized? true :__client c}
                             {})
      (let [list-parsed (json/parse-string
                         (tool/-invoke (get tools "mcp_list_servers") {} {})
                         true)]
        (is (true? (:ok list-parsed)))
        (is (= 1 (count (:servers list-parsed))))
        (is (true? (:dynamic-enabled? list-parsed))))
      (let [raw (tool/-invoke (get tools "mcp_remove_server")
                              {:server-id "x"} {})
            {:keys [results transitions]}
            (tr.ix/harvest-transitions
             [{:call {:id "1" :function {:name "mcp_remove_server"}}
               :result raw}])]
        (is (= 1 (count transitions)))
        (is (= :mcp-remove-server (:op (first transitions))))
        (is (= "x" (:server-id (first transitions))))
        (let [visible (json/parse-string (:result (first results)) true)]
          (is (true? (:ok visible)))))
      (finally
        (proto/halt-session! s)))))

(deftest control-tool-refresh-emits-transition
  (let [s (seeded-session)
        tools (session-tools/session-tools-registry s)]
    (try
      (let [raw (tool/-invoke (get tools "mcp_refresh_server")
                              {:server-id "fake"} {})
            harvested (tr.ix/harvest-transitions
                       [{:call {:id "1" :function {:name "mcp_refresh_server"}}
                         :result raw}])
            out (tr.ix/apply-queued-transitions
                 {:agent/mcp-session s
                  :agent/transitions (:transitions harvested)
                  :tool/results (:results harvested)
                  :agent/state {}
                  :agent/tool-registry (proto/-registry s)})
            parsed (json/parse-string (:result (first (:tool/results out))) true)]
        (is (true? (:ok parsed)))
        (is (= "mcp-refresh-server" (name (keyword (:op (:transition parsed))))))
        (is (contains? (set (:tools parsed)) "fake_echo")))
      (finally
        (proto/halt-session! s)))))

(deftest same-exchange-upsert-visible-to-follow-up
  (testing "mid-loop MCP upsert makes new tools visible on next LLM call"
    (let [{:keys [url stop!]} (fake-http/start! 0)
          s (dynamic-session)
          control (session-tools/session-tools-registry s)
          registry control
          server-cfg {:transport :http
                      :url url
                      :allow-http? true
                      :allow-loopback? true}
          seen-tool-sets (atom [])
          client (reify llm-client/LlmClient
                   (-call [_ req]
                     (let [names (tool-names-in-request req)
                           _ (swap! seen-tool-sets conj names)
                           step (count @seen-tool-sets)]
                       (cond
                         (= 1 step)
                         {:choices
                          [{:message
                            {:role "assistant"
                             :content ""
                             :tool_calls
                             [{:id "c1"
                               :type "function"
                               :function {:name "mcp_upsert_server"
                                          :arguments
                                          (json/generate-string
                                           {:server-id "dyn"
                                            :config server-cfg})}}]}}]}
                         (= 2 step)
                         (if (contains? names "dyn_echo")
                           {:choices
                            [{:message
                              {:role "assistant"
                               :content ""
                               :tool_calls
                               [{:id "c2"
                                 :type "function"
                                 :function {:name "dyn_echo"
                                            :arguments "{\"message\":\"hi\"}"}}]}}]}
                           {:choices
                            [{:message
                              {:role "assistant"
                               :content (str "missing dyn_echo in " (pr-str names))}}]})
                         :else
                         {:choices
                          [{:message {:role "assistant"
                                      :content "done via mcp"}}]}))))
          plugins [(plugins.base/base-plugin)
                   (plugins.tools/tools-plugin registry {:mcp-session s})]
          agent-map {:agent/llm-client client
                     :exchange-chain (plugin/assemble-chain plugins)
                     :initial-state {:model "m"
                                     :base-url "http://test"
                                     :agent/system-message "test"}
                     :agent/loop-opts {:max-loop-depth 5}}]
      (try
        (let [rt (runtime/start agent-map "sess-mcp-dyn")
              result (runtime/send-message rt "wire mcp then echo")
              stopped (runtime/stop rt)]
          (is (str/includes? (str (:exchange/response result)) "done via mcp"))
          (is (contains? (second @seen-tool-sets) "dyn_echo")
              "second LLM call should see dyn_echo after upsert")
          (is (= url (get-in stopped [:mcp/servers "dyn" :url]))
              "durable :mcp/servers records upsert"))
        (finally
          (proto/halt-session! s)
          (stop!))))))

