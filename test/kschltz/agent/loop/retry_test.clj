(ns kschltz.agent.loop.retry-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.llm.client :refer [LlmClient]]
            [kschltz.agent.loop.retry :as retry]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.plugins.tools :as plugins.tools]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.factory.session :as session]
            [kschltz.agent.tools.factory.tools :as factory.tools]
            [kschltz.agent.transitions :as tr]))

(def spec
  {:name "add_two"
   :description "Add two integers"
   :input-schema "[:map [:a :int] [:b :int]]"
   :invoke "(fn [args _ctx] (str (+ (:a args) (:b args))))"})

(defn- add-two-tool []
  (reify tool/Tool
    (-name [_] "add_two")
    (-description [_] "add")
    (-input-schema [_] [:map [:a :int] [:b :int]])
    (-output-schema [_] :string)
    (-invoke [_ args _] (str (+ (:a args) (:b args))))))

(deftest retry-reruns-just-registered-name
  (let [call {:id "2" :type "function"
              :function {:name "add_two" :arguments "{\"a\":1,\"b\":2}"}}
        ctx {:agent/tool-registry {"add_two" (add-two-tool)}
             :tool/results [{:call call
                             :result "Tool 'add_two' is not available in this session. Available tools: tool_define"}]
             :agent/all-tool-results [{:call call
                                       :result "Tool 'add_two' is not available in this session. Available tools: tool_define"}]}
        out (retry/retry-now-available ctx)]
    (is (= "3" (:result (first (:tool/results out)))))
    (is (= "3" (:result (first (:agent/all-tool-results out)))))))

(deftest nudge-when-defined-but-not-called
  (let [envelope (tr/encode-result
                  {:ok true
                   :tool "tool_define"
                   :tool-name "add_two"
                   :transition {:op :register-runtime-tool :spec spec}})
        ctx {:llm/request {:messages []}
             :tool/results [{:call {:function {:name "tool_define"}}
                             :result envelope}]}
        out (retry/nudge-untested-runtime-tools ctx)
        last-msg (peek (get-in out [:llm/request :messages]))]
    (is (= ["add_two"] (:agent/runtime-tool-test-nudge out)))
    (is (= "system" (:role last-msg)))
    (is (str/includes? (:content last-msg) "add_two"))
    (is (str/includes? (:content last-msg) "tool_test"))))

(deftest no-nudge-when-define-and-tool-test-both-ok
  (let [envelope (tr/encode-result
                  {:ok true
                   :tool "tool_define"
                   :tool-name "add_two"
                   :transition {:op :register-runtime-tool :spec spec}})
        ctx {:llm/request {:messages []}
             :tool/results [{:call {:function {:name "tool_define"}}
                             :result envelope}
                            {:call {:function {:name "tool_test"}}
                             :result (tr/encode-result
                                      {:ok true
                                       :tool "tool_test"
                                       :tool-name "add_two"
                                       :actual "3"})}]}
        out (retry/nudge-untested-runtime-tools ctx)]
    (is (nil? (:agent/runtime-tool-test-nudge out)))
    (is (empty? (get-in out [:llm/request :messages])))))

(deftest direct-call-does-not-satisfy-promotion-test-nudge
  (let [define-result
        (tr/encode-result
         {:ok true
          :tool "tool_define"
          :tool-name "add_two"
          :transition {:op :register-runtime-tool :spec spec}})
        out (retry/nudge-untested-runtime-tools
             {:llm/request {:messages []}
              :tool/results
              [{:call {:function {:name "tool_define"}}
                :result define-result}
               {:call {:function {:name "add_two"}}
                :result "3"}]})]
    (is (= ["add_two"] (:agent/runtime-tool-test-nudge out)))
    (is (str/includes? (get-in out [:llm/request :messages 0 :content])
                       "tool_test"))))

(deftest passing-tool-test-nudges-promotion-and-inventory
  (let [test-result
        (tr/encode-result {:ok true
                           :tool "tool_test"
                           :tool-name "add_two"
                           :actual "3"})
        out (retry/nudge-untested-runtime-tools
             {:llm/request {:messages []}
              :tool/results
              [{:call {:function {:name "tool_test"}}
                :result test-result}]})
        content (get-in out [:llm/request :messages 0 :content])]
    (is (= ["add_two"] (:agent/runtime-tool-promote-nudge out)))
    (is (str/includes? content "tool_promote"))
    (is (str/includes? content "tool_list_runtime"))
    (is (str/includes? content "Do not claim"))))

(deftest successful-promotion-nudges-inventory-verification
  (let [promote-result
        (tr/encode-result {:ok true
                           :tool "tool_promote"
                           :tool-name "add_two"
                           :paths {:tool "x"}})
        out (retry/nudge-untested-runtime-tools
             {:llm/request {:messages []}
              :tool/results
              [{:call {:function {:name "tool_promote"}}
                :result promote-result}]})
        content (get-in out [:llm/request :messages 0 :content])]
    (is (= ["add_two"] (:agent/runtime-tool-list-nudge out)))
    (is (str/includes? content "tool_list_runtime"))
    (is (str/includes? content "Do not claim"))))

(deftest same-turn-define-and-call-runs-new-tool
  (let [store (session/factory-session {})
        factory-reg (factory.tools/factory-tools-registry store)
        calls (atom 0)
        llm (reify LlmClient
              (-call [_ _req]
                (let [n (swap! calls inc)]
                  (if (= 1 n)
                    {:choices [{:message
                                {:role "assistant"
                                 :content ""
                                 :tool_calls
                                 [{:id "d1" :type "function"
                                   :function {:name "tool_define"
                                              :arguments (json/generate-string spec)}}
                                  {:id "c1" :type "function"
                                   :function {:name "add_two"
                                              :arguments "{\"a\":1,\"b\":2}"}}]}}]
                     :model "fake/v0"}
                    {:choices [{:message {:role "assistant" :content "3"}}]
                     :model "fake/v0"}))))
        chain (plugin/assemble-chain
               [(plugins.base/base-plugin)
                (plugins.tools/tools-plugin factory-reg {:factory-session store})])
        out (chain/execute
             {:agent/state {:base-url "stub" :api-key nil :model "fake/v0"
                            :agent/system-message "You have tools."}
              :llm/client llm
              :exchange/user-text "define add_two and use it"
              :agent/tool-loop-depth 0
              :exchange/session-id :retry-session
              :exchange/user-msg-id "u1"
              :exchange/assistant-msg-id "a1"}
             chain)
        results (:agent/all-tool-results out)
        add (some #(when (= "add_two" (get-in % [:call :function :name])) %)
                  results)]
    (is (= "3" (:exchange/response out)))
    (is (some? add))
    (is (= "3" (:result add)))
    (is (not (str/includes? (str (:result add)) "not available")))))

(deftest define-only-nudges-follow-up-to-test
  (let [store (session/factory-session {})
        factory-reg (factory.tools/factory-tools-registry store)
        reqs (atom [])
        llm (reify LlmClient
              (-call [_ req]
                (swap! reqs conj req)
                (if (= 1 (count @reqs))
                  {:choices [{:message
                              {:role "assistant"
                               :content ""
                               :tool_calls
                               [{:id "d1" :type "function"
                                 :function {:name "tool_define"
                                            :arguments (json/generate-string spec)}}]}}]
                   :model "fake/v0"}
                  {:choices [{:message {:role "assistant" :content "defined"}}]
                   :model "fake/v0"})))
        chain (plugin/assemble-chain
               [(plugins.base/base-plugin)
                (plugins.tools/tools-plugin factory-reg {:factory-session store})])]
    (chain/execute
     {:agent/state {:base-url "stub" :api-key nil :model "fake/v0"
                    :agent/system-message "You have tools."}
      :llm/client llm
      :exchange/user-text "define add_two"
      :agent/tool-loop-depth 0
      :exchange/session-id :nudge-session
      :exchange/user-msg-id "u1"
      :exchange/assistant-msg-id "a1"}
     chain)
    (is (>= (count @reqs) 2))
    (is (some #(and (= "system" (:role %))
                    (str/includes? (str (:content %)) "add_two")
                    (str/includes? (str (:content %)) "tool_test"))
              (:messages (second @reqs))))
    (is (some #(= "add_two" (get-in % [:function :name]))
              (:tools (second @reqs))))))
