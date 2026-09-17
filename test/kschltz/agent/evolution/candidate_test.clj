(ns kschltz.agent.evolution.candidate-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.evolution.candidate :as candidate]
            [kschltz.agent.evolution.protocol :as proto]
            [kschltz.agent.evolution.state :as state]
            [kschltz.agent.llm.client :as llm]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as base]
            [kschltz.agent.plugins.tools :as tools-plugin]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.filesystem :as filesystem])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftype NamedTool [tool-name]
  tool/Tool
  (-name [_] tool-name)
  (-description [_] tool-name)
  (-input-schema [_] [:map])
  (-output-schema [_] :map)
  (-invoke [_ args _] args))

(deftest candidate-turn-limit-is-enforced-by-the-runner
  (let [agent-map {:agent/llm-client (llm/stub-client)
                   :exchange-chain (plugin/assemble-chain [(base/base-plugin)])
                   :initial-state {}}
        runner (candidate/agent-candidate-runner agent-map)
        policy (assoc (state/normalize-policy nil) :max-agent-turns 1)
        result (proto/-implement!
                runner
                {:id "c1" :branch "evolve/c1" :worktree "/tmp" :base "main"}
                {:id "p1" :title "Task" :objective "Do work"
                 :evidence ["fixture"] :acceptance ["done"]}
                policy)]
    (is (= :budget-exhausted (:status result)))
    (is (= 1 (:turns result)))))

(deftest candidate-chain-is-rebuilt-from-an-explicit-safe-tool-allowlist
  (let [registry {"self_status" (->NamedTool "self_status")
                  "web_search" (->NamedTool "web_search")
                  "mcp_dynamic" (->NamedTool "mcp_dynamic")}
        agent-map {:exchange-chain
                   (plugin/assemble-chain
                    [(base/base-plugin)
                     (tools-plugin/tools-plugin registry)])
                   :initial-state {}}
        child (@#'candidate/candidate-agent-map
               agent-map
               {:id "c1" :branch "evolve/c1" :worktree "/tmp" :base "main"}
               (state/normalize-policy nil))
        seed (some #(when (:registry %) %) (:exchange-chain child))]
    (is (= #{"self_status"} (set (keys (:registry seed)))))))

(deftest candidate-write-tools-cannot-force-workspace-escape
  (is (= false
         (:force (tool/-invoke
                  (candidate/->CandidateTool (->NamedTool "file_write"))
                  {:force true} {})))))

(deftest candidate-file-tools-always-rebind-to-worktree
  (let [temp #(str (Files/createTempDirectory
                    %
                    (make-array FileAttribute 0)))
        original (temp "candidate-original-")
        worktree (temp "candidate-worktree-")
        _ (spit (io/file worktree "proof.txt") "candidate-root")
        registry (filesystem/filesystem-registry
                  {:workspace-root original})
        agent-map {:exchange-chain
                   (plugin/assemble-chain
                    [(base/base-plugin)
                     (tools-plugin/tools-plugin registry)])
                   :initial-state {}}
        child (@#'candidate/candidate-agent-map
               agent-map
               {:id "c1" :branch "evolve/c1"
                :worktree worktree :base "main"}
               (state/normalize-policy nil))
        seed (some #(when (:registry %) %) (:exchange-chain child))
        result (json/parse-string
                (tool/-invoke (get (:registry seed) "file_read")
                              {:path "proof.txt"} {})
                true)]
    (is (re-find #"candidate-root" (or (:content result) ""))
        (pr-str result))))
