(ns kschltz.agent.stream.plugin-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.llm.client :as llm :refer [LlmClient]]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.plugins.secrets :as plugins.secrets]
            [kschltz.agent.plugins.tools :as plugins.tools]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.secrets :as secrets]
            [kschltz.agent.stream.bus :as bus]
            [kschltz.agent.stream.plugin :as stream.plugin]
            [kschltz.agent.tool :as tool]))

(deftype AuditTool []
  tool/Tool
  (-name [_] "audit_echo")
  (-description [_] "Echo a value for stream audit tests.")
  (-input-schema [_] [:map [:value :string]])
  (-output-schema [_] :string)
  (-invoke [_ {:keys [value]} _ctx] value))

(defn- scripted-tool-client
  [value]
  (let [calls (atom 0)]
    (reify LlmClient
      (-call [_ _request]
        (if (= 1 (swap! calls inc))
          {:choices [{:message
                      {:role "assistant"
                       :content ""
                       :tool_calls
                       [{:id "audit-1"
                         :type "function"
                         :function {:name "audit_echo"
                                    :arguments (str "{\"value\":\"" value "\"}")}}]}}]
           :model "fake/v0"}
          {:choices [{:message {:role "assistant" :content "done"}}]
           :model "fake/v0"})))))

(deftest plugin-records-stub-exchange
  (let [b     (bus/create-bus)
        p     (stream.plugin/stream-plugin b)
        chain (plugin/assemble-chain [(plugins.base/base-plugin) p])
        rt    (runtime/start {:exchange-chain chain
                              :agent/llm-client (llm/stub-client)
                              :initial-state {:agent/system-message "sys"
                                              :model "stub/v0"}}
                             "stream-plugin-test")
        result (runtime/send-message rt "hello there")
        tid    (:stream/turn-id result)
        snap   (bus/snapshot b tid)]
    (is (string? tid))
    (is (= "done" (:status snap)))
    (is (re-find #"hello there" (:text snap)))
    (is (some #{"text-delta"} (map :type (:events snap))))
    (is (some #{"llm-done"} (map :type (:events snap))))))

(deftest second-exchange-opens-a-new-turn
  (let [b     (bus/create-bus)
        p     (stream.plugin/stream-plugin b)
        chain (plugin/assemble-chain [(plugins.base/base-plugin) p])
        rt    (runtime/start {:exchange-chain chain
                              :agent/llm-client (llm/stub-client)
                              :initial-state {:agent/system-message "sys"
                                              :model "stub/v0"}}
                             "stream-plugin-test-2")
        a (:stream/turn-id (runtime/send-message rt "one"))
        b-id (:stream/turn-id (runtime/send-message rt "two"))]
    (is (string? a))
    (is (string? b-id))
    (is (not= a b-id))
    (is (= "done" (:status (bus/snapshot b a))))
    (is (= "done" (:status (bus/snapshot b b-id))))))

(deftest empty-plugin-when-bus-nil
  (is (= [] (stream.plugin/stream-plugin nil))))

(deftest assembled-chain-emits-tool-result-content
  (let [b (bus/create-bus)
        chain (plugin/assemble-chain
               [(plugins.base/base-plugin)
                (plugins.tools/tools-plugin {"audit_echo" (->AuditTool)})
                (stream.plugin/stream-plugin b)])
        rt (runtime/start {:exchange-chain chain
                           :agent/llm-client (scripted-tool-client "guarded-result")
                           :initial-state {:agent/system-message "sys"
                                           :model "fake/v0"}}
                          "stream-tool-result-test")
        result (runtime/send-message rt "call the audit tool")
        turn-id (:stream/turn-id result)]
    (let [events (->> (:events (bus/snapshot b turn-id))
                      (filter #(= "tool-result" (:type %)))
                      vec)]
      (is (= 1 (count events)))
      (is (= "audit_echo" (:tool-name (first events))))
      (is (= "guarded-result" (:tool-result (first events)))))))

(deftest assembled-chain-emits-only-redacted-tool-result-content
  (let [path (str (System/getProperty "java.io.tmpdir")
                  "/stream-audit-secrets-" (random-uuid) ".sealed")
        raw-secret "stream-audit-secret-value"
        store (secrets/sealed-file-store
               {:path path :passphrase "test-passphrase" :kdf-iterations 1000})]
    (try
      (secrets/-put-secret! store "tok" raw-secret)
      (let [b (bus/create-bus)
            chain (plugin/assemble-chain
                   [(plugins.base/base-plugin)
                    (plugins.tools/tools-plugin {"audit_echo" (->AuditTool)})
                    (plugins.secrets/secrets-plugin
                     {:store store
                      :capabilities {"audit_echo" {:labels #{"tok"}}}})
                    (stream.plugin/stream-plugin b)])
            rt (runtime/start {:exchange-chain chain
                               :agent/llm-client
                               (scripted-tool-client "{{secret:tok}}")
                               :initial-state {:agent/system-message "sys"
                                               :model "fake/v0"}}
                              "stream-redaction-test")
            result (runtime/send-message rt "call the audit tool")
            events (->> (:events (bus/snapshot b (:stream/turn-id result)))
                        (filter #(= "tool-result" (:type %)))
                        vec)]
        (is (= 1 (count events)))
        (is (= "[REDACTED:tok]" (:tool-result (first events))))
        (is (not (str/includes? (str (:tool-result (first events)))
                                raw-secret))))
      (finally
        (.delete (java.io.File. path))))))
