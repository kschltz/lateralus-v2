(ns kschltz.agent.tools.sub-agent-test
  "Tests for the spawn_sub_agent tool."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.llm.client :as llm-client]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.plugins.tools :as plugins.tools]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.sub-agent :as sub-agent]))

(deftype EchoTool []
  tool/Tool
  (-name [_] "echo")
  (-description [_] "echoes input")
  (-input-schema [_] [:map [:x :string]])
  (-output-schema [_] :string)
  (-invoke [_ args _] (:x args)))

(defn- ctx
  "Build a minimal interceptor context for the sub-agent tool."
  [chain tool-registry llm-client]
  {:agent/state       {:agent/system-message "You are a helpful assistant."
                      :agent/token-usage    {:prompt_tokens 0
                                           :completion_tokens 0
                                           :total_tokens 0}
                      :agent/history        []}
   :llm/client        llm-client
   :agent/tool-registry tool-registry
   :agent/agent-map   {:exchange-chain chain
                      :agent/llm-client llm-client}})

(deftest sub-agent-tool-definition
  (testing "the tool produces a valid OpenAI-shaped definition"
    (let [t (sub-agent/sub-agent-tool)
          def (tool/tool-definition t)]
      (is (= "spawn_sub_agent" (get-in def [:function :name])))
      (is (= "function" (:type def)))
      (is (some? (get-in def [:function :parameters :properties :task]))))))

(deftest sub-agent-invokes-child-runtime
  (testing "the tool spawns a child runtime and returns a JSON summary"
    (let [echo-tool    (->EchoTool)
          sub-tool     (sub-agent/sub-agent-tool)
          registry     {"echo" echo-tool "spawn_sub_agent" sub-tool}
          chain        (plugin/assemble-chain [(plugins.base/base-plugin)
                                              (plugins.tools/tools-plugin registry)])
          llm-client   (llm-client/stub-client)
          result       (tool/invoke-tool sub-tool
                                         {:task "echo hello"}
                                         (ctx chain registry llm-client))
          parsed       (json/parse-string result true)]
      (is (string? result))
      (is (= "echo hello" (:task parsed)))
      (is (true? (:success? parsed)))
      (is (contains? parsed :response))
      (is (contains? parsed :token-usage)))))

(deftest sub-agent-excludes-itself-from-child
  (testing "the child runtime cannot recursively spawn sub-agents"
    (let [sub-tool     (sub-agent/sub-agent-tool)
          registry     {"spawn_sub_agent" sub-tool}
          chain        (plugin/assemble-chain [(plugins.base/base-plugin)
                                              (plugins.tools/tools-plugin registry)])
          llm-client   (llm-client/stub-client)
          result       (tool/invoke-tool sub-tool
                                         {:task "list available tools"}
                                         (ctx chain registry llm-client))
          parsed       (json/parse-string result true)]
      (is (string? (:response parsed))))))

(deftest sub-agent-registry-contains-one-tool
  (testing "sub-agent-registry returns the spawn_sub_agent tool"
    (let [registry (sub-agent/sub-agent-registry)]
      (is (= 1 (count registry)))
      (is (contains? registry "spawn_sub_agent"))
      (is (tool/tool? (get registry "spawn_sub_agent"))))))

(deftest child-chain-excludes-sub-agent-tool
  (testing "the child runtime's chain is rebuilt with a filtered registry"
    (let [echo-tool    (->EchoTool)
          sub-tool     (sub-agent/sub-agent-tool)
          registry     {"echo" echo-tool "spawn_sub_agent" sub-tool}
          chain        (plugin/assemble-chain [(plugins.base/base-plugin)
                                              (plugins.tools/tools-plugin registry)])
          llm-client   (llm-client/stub-client)
          parent-ctx   (ctx chain registry llm-client)
          child-map    (@#'sub-agent/child-agent-map parent-ctx 3)
          seed         (some #(when (= ::plugins.tools/seed-registry (:name %)) %)
                            (:exchange-chain child-map))]
      (is (some? seed) "child chain should contain the tools seed interceptor")
      (let [child-ctx ((:enter seed) {})]
        (is (not (contains? (:agent/tool-registry child-ctx) "spawn_sub_agent"))
            "child must not inherit the spawn_sub_agent tool")
        (is (contains? (:agent/tool-registry child-ctx) "echo"))))))
