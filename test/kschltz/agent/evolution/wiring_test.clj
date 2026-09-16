(ns kschltz.agent.evolution.wiring-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [kschltz.agent.evolution.protocol :as proto]
            [kschltz.agent.evolution.wiring])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest evolution-capabilities-are-integrant-managed
  (let [root (str (Files/createTempDirectory
                   "lateralus-evolution-wiring-"
                   (make-array FileAttribute 0)))
        config
        {:lateralus/evolution-store
         {:path (str (io/file root "audit.duckdb"))}
         :lateralus/evolution-command-runner
         {:allowed-programs #{"git" "kb"}
          :allowed-roots [root]}
         :lateralus/evolution-board
         {:runner (ig/ref :lateralus/evolution-command-runner)
          :repository-root root
          :agent "test"}
         :lateralus/evolution-workspace
         {:runner (ig/ref :lateralus/evolution-command-runner)
          :repository-root root
          :worktree-parent (str (io/file root "worktrees"))
          :base-branch "main"}
         :lateralus/evolution-ledger
         {:store (ig/ref :lateralus/evolution-store)}
         :lateralus/evolution-evaluator
         {:runner (ig/ref :lateralus/evolution-command-runner)}
         :lateralus/evolution-candidate
         {:agent-map {:initial-state {}}}}
        system (ig/init config)]
    (try
      (is (proto/command-runner?
           (:lateralus/evolution-command-runner system)))
      (is (proto/board? (:lateralus/evolution-board system)))
      (is (proto/workspace-manager?
           (:lateralus/evolution-workspace system)))
      (is (proto/evolution-ledger?
           (:lateralus/evolution-ledger system)))
      (is (proto/evaluator? (:lateralus/evolution-evaluator system)))
      (is (proto/candidate-runner?
           (:lateralus/evolution-candidate system)))
      (finally
        (ig/halt! system)))))
