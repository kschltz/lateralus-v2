(ns kschltz.agent.tools.mcp.http-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fake-mcp-http-server :as fake-http]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.mcp.client :as client]
            [kschltz.agent.tools.mcp.http :as http]
            [kschltz.agent.tools.mcp.protocol :as proto]
            [kschltz.agent.tools.mcp.tools :as tools]))

(deftest parse-sse-data-extracts-messages
  (let [body (str "event: message\n"
                  "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n\n"
                  "data: not-json\n\n")
        msgs (http/parse-sse-data body)]
    (is (= 1 (count msgs)))
    (is (= 1 (:id (first msgs))))
    (is (true? (get-in (first msgs) [:result :ok])))))

(deftest build-headers-bearer
  (let [h (http/build-headers {:bearer-token "secret"
                               :headers {"X-Extra" "1"}})]
    (is (= "Bearer secret" (get h "Authorization")))
    (is (= "1" (get h "X-Extra")))
    (is (str/includes? (get h "Accept") "application/json"))
    (is (= "2024-11-05" (get h "MCP-Protocol-Version")))))

(deftest http-round-trip-json
  (let [{:keys [url stop!]} (fake-http/start! 0)]
    (try
      (let [c (client/connect-http!
               {:url url
                :allow-http? true
                :allow-loopback? true
                :server-id "remote"
                :startup-timeout-ms 10000
                :request-timeout-ms 10000})]
        (try
          (is (= "fake-mcp-server" (:name (proto/-server-info c))))
          (let [tools (proto/-list-tools c)
                names (set (map :name tools))]
            (is (contains? names "echo"))
            (is (contains? names "add")))
          (let [echo (proto/-call-tool c "echo" {:message "via-http"})]
            (is (false? (:isError echo)))
            (is (= "via-http" (get-in echo [:content 0 :text]))))
          (finally
            (proto/close! c))))
      (finally
        (stop!)))))

(deftest http-round-trip-sse
  (let [{:keys [url stop!]} (fake-http/start! 0)]
    (try
      (let [c (client/connect-http!
               {:url url
                :allow-http? true
                :allow-loopback? true
                :headers {"Prefer" "sse"}
                :server-id "remote-sse"
                :startup-timeout-ms 10000
                :request-timeout-ms 10000})]
        (try
          (is (>= (count (proto/-list-tools c)) 2))
          (is (= "sse-hi"
                 (get-in (proto/-call-tool c "echo" {:message "sse-hi"})
                         [:content 0 :text])))
          (finally
            (proto/close! c))))
      (finally
        (stop!)))))

(deftest http-auth-phase
  (testing "401 surfaces :phase :auth"
    (let [http-fn (fn [_req]
                    {:status 401 :headers {} :body "nope"})]
      (try
        (http/post-message!
         "http://127.0.0.1:9/mcp"
         (http/build-headers {})
         {:jsonrpc "2.0" :id 1 :method "tools/list" :params {}}
         1000
         http-fn)
        (is false "expected auth error")
        (catch Exception e
          (is (= :auth (:phase (ex-data e)))))))))

(deftest registry-http-echo-tool
  (let [{:keys [url stop!]} (fake-http/start! 0)]
    (try
      (let [reg (tools/mcp-registry
                 {:servers
                  {"remote"
                   {:transport :http
                    :url url
                    :allow-http? true
                    :allow-loopback? true}}})]
        (try
          (is (contains? reg "remote_echo"))
          (let [out (json/parse-string
                     (tool/-invoke (get reg "remote_echo")
                                   {:message "reg-http"}
                                   {})
                     true)]
            (is (= "reg-http" (:content out)))
            (is (= "ok" (:status out))))
          (finally
            (tools/halt-registry! reg))))
      (finally
        (stop!)))))

(deftest ssrf-blocks-http-connect
  (is (thrown-with-msg?
       Exception #"rejected|SSRF|URL"
       (client/connect-http!
        {:url "http://127.0.0.1:1/mcp"
         :server-id "blocked"}))))
