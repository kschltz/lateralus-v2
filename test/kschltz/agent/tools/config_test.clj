(ns kschltz.agent.tools.config-test
  "Tests for set_llm_config / list_llm_models tools and their
   integration through harvest → apply."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.llm.client :as llm-client]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.plugins.tools :as plugins.tools]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.config :as tools.config]
            [kschltz.agent.tools.config.catalog :as catalog]
            [kschltz.agent.transitions.interceptors :as tr.ix]))

(defn- cfg-registry
  ([] (tools.config/config-registry {:catalog (catalog/stub-catalog ["stub/a" "stub/b"])}))
  ([cat] (tools.config/config-registry {:catalog cat})))

(deftest config-registry-has-session-configuration-tools
  (let [reg (cfg-registry)]
    (is (= #{"set_llm_config" "set_system_message"
             "set_loop_policy" "set_tool_enabled" "set_memory_policy"
             "list_llm_models"}
           (set (keys reg))))
    (is (every? tool/tool? (vals reg)))))

(deftest set-llm-config-emits-transition
  (let [t (get (cfg-registry) "set_llm_config")
        ctx {:agent/state {:model "old" :base-url "http://old"}}
        result (tool/invoke-tool t {:model "new"} ctx)
        parsed (json/parse-string result true)]
    (is (true? (:ok parsed)))
    (is (= "same-exchange" (:pending parsed)))
    (is (= "set-llm" (name (keyword (:op (:transition parsed))))))
    (is (= "new" (get-in parsed [:transition :model])))
    (is (= "old" (get-in parsed [:before :model])))
    (is (= "new" (get-in parsed [:after :model])))))

(deftest set-llm-config-requires-a-field
  (let [t (get (cfg-registry) "set_llm_config")
        result (tool/invoke-tool t {} {:agent/state {}})]
    (is (str/includes? result "input validation failed"))))

(deftest set-llm-config-hides-api-key-after-harvest
  (let [t (get (cfg-registry) "set_llm_config")
        raw (tool/invoke-tool t {:api-key "super-secret"} {:agent/state {}})
        {:keys [results transitions]}
        (tr.ix/harvest-transitions
         [{:call {:id "1" :function {:name "set_llm_config"}} :result raw}])
        visible (json/parse-string (:result (first results)) true)]
    (is (= "super-secret" (:api-key (first transitions))))
    (is (nil? (get-in visible [:transition :api-key])))
    (is (true? (get-in visible [:transition :api-key-set])))))

(deftest runtime-policy-tools-emit-allowlisted-transitions
  (let [reg (cfg-registry)
        msg-result
        (-> (tool/invoke-tool
             (get reg "set_system_message")
             {:message "new system"}
             {:agent/state {:agent/system-message "old"}})
            (json/parse-string true))
        loop-result
        (-> (tool/invoke-tool
             (get reg "set_loop_policy")
             {:max-loop-depth 8
              :tool-content-caps {"file_read" 8192}}
             {:agent/loop-opts {:max-loop-depth 3}})
            (json/parse-string true))]
    (is (= "set-system-message"
           (name (keyword (get-in msg-result [:transition :op])))))
    (is (= "new system" (get-in msg-result [:after :message])))
    (is (= "set-loop-opts"
           (name (keyword (get-in loop-result [:transition :op])))))
    (is (= 3 (get-in loop-result [:before :max-loop-depth])))
    (is (= 8 (get-in loop-result [:after :max-loop-depth])))))

(deftest runtime-policy-tools-reject-empty-or-unknown-input
  (let [reg (cfg-registry)]
    (is (str/includes?
         (tool/invoke-tool (get reg "set_system_message")
                           {:message ""} {})
         "input validation failed"))
    (is (str/includes?
         (tool/invoke-tool (get reg "set_loop_policy") {} {})
         "input validation failed"))
    (is (str/includes?
         (tool/invoke-tool (get reg "set_loop_policy")
                           {:unknown 1} {})
         "input validation failed"))))

(deftest set-tool-enabled-validates-registry-and-protects-recovery
  (let [reg (cfg-registry)
        t (get reg "set_tool_enabled")
        known {"file_write" :fake
               "set_tool_enabled" t
               "runtime_describe" :fake}
        ctx {:agent/static-tool-registry known
             :agent/state {}}
        disabled (-> (tool/invoke-tool t
                                       {:tool-name "file_write"
                                        :enabled false}
                                       ctx)
                     (json/parse-string true))
        unknown (-> (tool/invoke-tool t
                                      {:tool-name "missing"
                                       :enabled false}
                                      ctx)
                    (json/parse-string true))
        protected (-> (tool/invoke-tool t
                                        {:tool-name "runtime_describe"
                                         :enabled false}
                                        ctx)
                      (json/parse-string true))]
    (is (true? (:ok disabled)))
    (is (= "set-tool-enabled"
           (name (keyword (get-in disabled [:transition :op])))))
    (is (false? (get-in disabled [:after :enabled])))
    (is (= "unknown-tool" (:error unknown)))
    (is (= "protected-tool" (:error protected)))
    (is (nil? (:transition protected)))))

(deftest set-memory-policy-emits-closed-transition
  (let [reg (cfg-registry)
        t (get reg "set_memory_policy")
        result (-> (tool/invoke-tool
                    t
                    {:top-y 8
                     :recall-enabled false}
                    {:agent/state
                     {:agent/memory-policy {:last-n 3}}})
                   (json/parse-string true))]
    (is (true? (:ok result)))
    (is (= 3 (get-in result [:before :last-n])))
    (is (= 8 (get-in result [:after :top-y])))
    (is (false? (get-in result [:after :recall-enabled])))
    (is (= "set-memory-policy"
           (name (keyword (get-in result [:transition :op])))))
    (is (str/includes?
         (tool/invoke-tool t {} {})
         "input validation failed"))))

(deftest list-llm-models-uses-catalog
  (let [t (get (cfg-registry) "list_llm_models")
        result (tool/invoke-tool t {} {:agent/state {:base-url "http://x"}})
        parsed (json/parse-string result true)]
    (is (true? (:ok parsed)))
    (is (= ["stub/a" "stub/b"] (:models parsed)))
    (is (= 2 (:count parsed)))))

(deftest list-llm-models-surfaces-catalog-errors
  (let [boom (reify catalog/ModelCatalog
               (-list-models [_ _]
                 (throw (ex-info "nope" {:kind :http-error}))))
        t (get (cfg-registry boom) "list_llm_models")
        parsed (json/parse-string
                (tool/invoke-tool t {} {:agent/state {:base-url "http://x"}})
                true)]
    (is (false? (:ok parsed)))
    (is (= "nope" (:error parsed)))))

(deftest end-to-end-set-model-via-runtime
  (testing "set_llm_config through the real chain updates runtime state and request model"
    (let [reg (cfg-registry)
          ;; Minimal stub client that records the model on each call and,
          ;; on the first call, requests set_llm_config.
          calls (atom [])
          client (reify llm-client/LlmClient
                   (-call [_ req]
                     (swap! calls conj (:model req))
                     (if (= 1 (count @calls))
                       {:choices
                        [{:message
                          {:role "assistant"
                           :content ""
                           :tool_calls
                           [{:id "c1"
                             :type "function"
                             :function {:name "set_llm_config"
                                        :arguments "{\"model\":\"switched\"}"}}]}}]
                        :model (:model req)}
                       {:choices
                        [{:message {:role "assistant"
                                    :content (str "using " (:model req))}}]
                        :model (:model req)})))
          plugins [(plugins.base/base-plugin)
                   (plugins.tools/tools-plugin reg)]
          agent-map {:agent/llm-client client
                     :exchange-chain (plugin/assemble-chain plugins)
                     :initial-state {:model "original"
                                     :base-url "http://test"
                                     :agent/system-message "test"}
                     :agent/loop-opts {:max-loop-depth 3}}
          rt (runtime/start agent-map "sess-config")
          result (runtime/send-message rt "please switch model")
          stopped (runtime/stop rt)]
      (is (= ["original" "switched"] @calls))
      (is (= "switched" (:model stopped)))
      (is (str/includes? (str (:exchange/response result)) "switched")))))
