(ns kschltz.agent.workbench.settings-http-test
  "Tests for the runtime settings HTTP surface (view + apply + models)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.workbench.hub :as hub]
            [kschltz.agent.workbench.http :as http]
            [kschltz.agent.workbench.settings-http :as settings]))

(defn- fake-agent-map
  "agent-map with a tools plugin whose seed interceptor carries a static
  registry of two stub tools."
  []
  (let [tool-a (reify kschltz.agent.tool/Tool
                 (-name [_] "tool_a")
                 (-description [_] "Does A things")
                 (-input-schema [_] [:map])
                 (-output-schema [_] :string)
                 (-invoke [_ _ _] "{}"))
        tool-b (reify kschltz.agent.tool/Tool
                 (-name [_] "tool_b")
                 (-description [_] "Does B things")
                 (-input-schema [_] [:map])
                 (-output-schema [_] :string)
                 (-invoke [_ _ _] "{}"))]
    {:agent/plugins
     [(with-meta
        [{:name ::seed :registry {"tool_a" tool-a "tool_b" tool-b}}]
        {:plugin/name :tools})]}))

(defn- test-runtime
  "Runtime with the same initial-state shape the system builds."
  []
  (runtime/start (assoc (fake-agent-map)
                        :initial-state {:model "m0" :base-url "http://x/v1"
                                        :api-key "secret"})))

(defn handler-with-settings
  []
  (let [rt  (test-runtime)
        h   (hub/create-hub {:session-id "settings-test"})
        ops {:view-fn   #(settings/settings-view rt)
             :apply-fn  #(settings/apply-op! h rt %)
             :models-fn (fn [_] {:models ["fake/m1" "fake/m2"]})}]
    {:handler (http/make-handler h {:settings-ops ops})
     :runtime rt
     :hub     h}))

(defn req
  ([handler method uri]
   (handler {:request-method method :uri uri}))
  ([handler method uri body]
   (handler {:request-method method :uri uri
             :body (json/generate-string body)})))

(deftest settings-view-round-trips-and-redacts
  (let [{:keys [handler]} (handler-with-settings)
        res  (req handler :get "/api/settings")
        body (json/parse-string (:body res) true)]
    (is (= 200 (:status res)))
    (is (= "m0" (get-in body [:llm :model])))
    (is (true? (get-in body [:llm :api-key-set])))
    (is (nil? (get-in body [:llm :api-key])) "api key never serialized")
    (is (= 2 (count (:tools body))))))

(deftest settings-apply-set-llm
  (let [{:keys [handler]} (handler-with-settings)
        res  (req handler :post "/api/settings"
                  {:op {:op :set-llm :model "m1"}})
        body (json/parse-string (:body res) true)]
    (is (= 200 (:status res)))
    (is (true? (:ok body)))
    (let [view (json/parse-string (:body (req handler :get "/api/settings")) true)]
      (is (= "m1" (get-in view [:llm :model])))
      (is (= "http://x/v1" (get-in view [:llm :base-url]))
          "untouched keys persist"))))

(deftest settings-apply-rejects-bad-ops
  (let [{:keys [handler]} (handler-with-settings)]
    (testing "unknown op"
      (let [res  (req handler :post "/api/settings" {:op {:op {:op :mcp-upsert-server :server-id "x" :config {}}}})
            body (json/parse-string (:body res) true)]
        (is (= 400 (:status res)))
        (is (false? (:ok body)))))
    (testing "invalid payload for known op (set-llm without any key)"
      (let [res  (req handler :post "/api/settings" {:op {:op :set-llm}})
            body (json/parse-string (:body res) true)]
        (is (= 400 (:status res)))
        (is (false? (:ok body)))))
    (testing "non-map op"
      (let [res  (req handler :post "/api/settings" {:op "nope"})]
        (is (= 400 (:status res)))))))

(deftest settings-apply-tool-toggle-and-system-message
  (let [{:keys [handler]} (handler-with-settings)]
    (req handler :post "/api/settings"
         {:op {:op "set-tool-enabled" "tool-name" "tool_a" :enabled false}})
    (let [view (json/parse-string (:body (req handler :get "/api/settings")) true)
          tool (some #(when (= "tool_a" (:name %)) %) (:tools view))]
      (is (false? (:enabled tool))))
    (req handler :post "/api/settings"
         {:op {:op "set-system-message" :message "custom prompt"}})
    (let [view (json/parse-string (:body (req handler :get "/api/settings")) true)]
      (is (= "custom prompt" (:system-message view))))))

(deftest settings-models-endpoint
  (let [{:keys [handler]} (handler-with-settings)
        res  (req handler :get "/api/settings/models")
        body (json/parse-string (or (:body res) "{}") true)]
    (is (= 200 (:status res)))
    (is (= ["fake/m1" "fake/m2"] (vec (:models body))))))