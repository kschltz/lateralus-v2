(ns kschltz.agent.evolution.worktree-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.evolution.process :as process]
            [kschltz.agent.evolution.protocol :as proto]
            [kschltz.agent.evolution.worktree :as worktree])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (str (Files/createTempDirectory
        "lateralus-evolution-git-"
        (make-array FileAttribute 0))))

(defn- git!
  [runner cwd argv]
  (let [result (proto/-run-command!
                runner {:argv (into ["git"] argv)
                        :cwd cwd :timeout-ms 10000
                        :max-output-bytes 65536 :network :deny})]
    (is (= :ok (:status result)) (pr-str result))
    result))

(deftest candidate-lives-on-an-isolated-local-branch
  (let [root (temp-dir)
        repo (str (io/file root "repo"))
        worktrees (str (io/file root "worktrees"))
        _ (.mkdirs (io/file repo))
        runner (process/local-command-runner
                {:allowed-programs #{"git"}
                 :allowed-roots [root]})
        _ (git! runner repo ["init" "-b" "main"])
        _ (spit (io/file repo "README.md") "base\n")
        _ (.mkdirs (io/file repo "test"))
        _ (spit (io/file repo "test" "protected.txt") "holdout\n")
        _ (git! runner repo ["add" "--all"])
        _ (git! runner repo ["-c" "user.name=Test"
                             "-c" "user.email=test@localhost"
                             "commit" "-m" "base"])
        manager (worktree/git-workspace-manager
                 {:runner runner :repository-root repo
                  :worktree-parent worktrees :base-branch "main"})
        candidate (proto/-create-candidate!
                   manager "run-1"
                   {:id "proposal-1" :title "Change README"
                    :objective "Update text" :evidence ["fixture"]
                    :acceptance ["text changes"]})]
    (spit (io/file (:worktree candidate) "README.md") "changed\n")
    (spit (io/file (:worktree candidate) "README.md.bak.123") "base\n")
    (spit (io/file (:worktree candidate) "missing.txt.bak.123") "keep\n")
    (.mkdirs (io/file (:worktree candidate) "src"))
    (is (.renameTo (io/file (:worktree candidate) "test" "protected.txt")
                   (io/file (:worktree candidate) "src" "moved.txt")))
    (is (= ["README.md.bak.123"]
           (proto/-clean-ephemeral! manager candidate)))
    (is (not (.exists (io/file (:worktree candidate)
                               "README.md.bak.123"))))
    (is (= ["README.md" "missing.txt.bak.123"
            "src/moved.txt" "test/protected.txt"]
           (proto/-changed-paths manager candidate)))
    (proto/-snapshot-candidate! manager candidate "candidate")
    (is (re-find #"changed" (proto/-diff manager candidate)))
    (is (false? (:already-promoted?
                 (proto/-promote-candidate! manager candidate repo))))
    (is (true? (:already-promoted?
                (proto/-promote-candidate! manager candidate repo))))
    (is (= "changed\n" (slurp (io/file repo "README.md"))))
    (is (false? (:already-discarded?
                 (proto/-discard-candidate! manager candidate))))
    (is (true? (:already-discarded?
                (proto/-discard-candidate! manager candidate))))
    (is (not (.exists (io/file (:worktree candidate)))))))
