(ns kschltz.agent.runtime-harness-e2e-test
  "Deterministic offline proof of runtime transitions plus snapshot editing."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.llm.client :as llm]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.plugins.tools :as plugins.tools]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.tools.config :as tools.config]
            [kschltz.agent.tools.config.catalog :as catalog]
            [kschltz.agent.tools.filesystem :as filesystem]
            [kschltz.agent.tools.self :as tools.self])
  (:import [java.io File]))

(defn- tool-call
  [id name arguments]
  {:id id
   :type "function"
   :function {:name name
              :arguments (json/generate-string arguments)}})

(defn- response
  [message]
  {:choices [{:message (merge {:role "assistant"} message)}]
   :model "offline-harness"})

(defn- latest-tool-result
  [request]
  (some->> (:messages request)
           reverse
           (filter #(= "tool" (:role %)))
           first
           :content
           (json/parse-string true)))

(deftest ^:e2e runtime-controls-and-snapshot-patch-flow-through-real-chain
  (let [workspace (doto (File/createTempFile "lateralus-runtime-e2e" "")
                    (.delete)
                    (.mkdirs))
        target (io/file workspace "notes.txt")
        _ (spit target "alpha\nbeta\ngamma\n")
        calls (atom 0)
        client
        (reify llm/LlmClient
          (-call [_ request]
            (case (swap! calls inc)
              1
              (response
               {:content ""
                :tool_calls
                [(tool-call "describe" "runtime_describe" {:section "chain"})
                 (tool-call "system" "set_system_message"
                            {:message "runtime edited through interceptors"})
                 (tool-call "loop" "set_loop_policy"
                            {:max-loop-depth 9})
                 (tool-call "memory" "set_memory_policy"
                            {:recall-enabled false})]})

              2
              (response
               {:content ""
                :tool_calls [(tool-call "read" "file_read"
                                        {:path "notes.txt"})]})

              3
              (let [read-result (latest-tool-result request)]
                (response
                 {:content ""
                  :tool_calls
                  [(tool-call "patch" "file_patch"
                              {:path "notes.txt"
                               :expected-sha256 (:sha256 read-result)
                               :patches [{:start-line 2
                                          :end-line 2
                                          :replacement "BETA\n"}]})]}))

              (response {:content "runtime and file updated"}))))
        registry (merge
                  (filesystem/filesystem-registry
                   {:workspace-root (.getAbsolutePath workspace)})
                  (tools.self/self-awareness-registry
                   (.getAbsolutePath workspace))
                  (tools.config/config-registry
                   {:catalog (catalog/stub-catalog)}))
        chain (plugin/assemble-chain
               [(plugins.base/base-plugin)
                (plugins.tools/tools-plugin registry)])
        r (runtime/start
           {:agent/llm-client client
            :agent/loop-opts {:max-loop-depth 5}
            :exchange-chain chain
            :initial-state {:model "offline-harness"
                            :base-url "stub"
                            :agent/system-message "initial"}}
           "runtime-harness-e2e")]
    (try
      (let [result (runtime/send-message r "Update the runtime and notes file")
            state (runtime/stop r)
            names (mapv #(get-in % [:call :function :name])
                        (:agent/all-tool-results result))]
        (is (= "runtime and file updated" (:exchange/response result)))
        (is (= 4 @calls))
        (is (= "alpha\nBETA\ngamma\n" (slurp target)))
        (is (= "runtime edited through interceptors"
               (:agent/system-message state)))
        (is (= 9 (get-in state [:agent/loop-opts :max-loop-depth])))
        (is (false? (get-in state
                            [:agent/memory-policy :recall-enabled])))
        (is (= ["runtime_describe"
                "set_system_message"
                "set_loop_policy"
                "set_memory_policy"
                "file_read"
                "file_patch"]
               names)))
      (finally
        (doseq [^File entry (reverse (file-seq workspace))]
          (.delete entry))))))
