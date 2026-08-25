(ns kschltz.agent.runtime-harness-e2e-test
  "Deterministic offline proof of runtime transitions plus snapshot editing."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [kschltz.agent.llm.client :as llm]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.system :as system])
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
  (when-let [content (some->> (:messages request)
                              reverse
                              (filter #(= "tool" (some-> (:role %) name)))
                              first
                              :content)]
    (json/parse-string content true)))

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
                            {:recall-enabled false})
                 (tool-call "model" "set_llm_config"
                            {:model "edited-model"})
                 (tool-call "tools" "set_tool_enabled"
                            {:tool-name "file_glob"
                             :enabled false})
                 (tool-call "reload" "reload_runtime"
                            {:namespaces
                             ["kschltz.agent.interceptors"]})]})

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
        root (.getAbsolutePath workspace)
        config (-> system/default-config
                   (assoc :lateralus/llm-client
                          {:impl :provided :client client}
                          :lateralus/llm-config
                          {:model "offline-harness" :base-url "stub"}
                          :lateralus/file-tools {:workspace-root root}
                          :lateralus/self-awareness-tools
                          {:workspace-root root}
                          :lateralus/clojure-tools {:workspace-root root}
                          :lateralus/config-tools {:catalog :stub}))
        ig-system (ig/init config)
        agent (:lateralus/agent ig-system)
        r (runtime/start agent "runtime-harness-e2e")]
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
        (is (= "edited-model" (:model state)))
        (is (= ["file_glob"] (:agent/disabled-tools state)))
        (is (false? (get-in state
                            [:agent/memory-policy :recall-enabled])))
        (is (= :reloaded
               (get-in state
                       [:agent/runtime-reload-status :status])))
        (let [described (-> (:agent/all-tool-results result)
                            first
                            :result
                            (json/parse-string true))]
          (is (seq (:chain described)))
          (is (every? :name (:chain described))))
        (is (= ["runtime_describe"
                "set_system_message"
                "set_loop_policy"
                "set_memory_policy"
                "set_llm_config"
                "set_tool_enabled"
                "reload_runtime"
                "file_read"
                "file_patch"]
               names)))
      (finally
        (ig/halt! ig-system)
        (doseq [^File entry (reverse (file-seq workspace))]
          (.delete entry))))))
