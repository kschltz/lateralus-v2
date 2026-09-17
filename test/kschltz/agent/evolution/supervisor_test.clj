(ns kschltz.agent.evolution.supervisor-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.evolution.evaluator :as evaluator]
            [kschltz.agent.evolution.ledger :as ledger]
            [kschltz.agent.evolution.process :as process]
            [kschltz.agent.evolution.protocol :as proto]
            [kschltz.agent.evolution.supervisor :as supervisor]
            [kschltz.agent.evolution.worktree :as worktree]
            [kschltz.agent.store.memory :as memory])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defrecord FakeBoard [actions]
  proto/Board
  (-context [_ card-id] {:id card-id :worktree "/repo/card"})
  (-decision! [_ _ message] (swap! actions conj [:decision message]))
  (-handoff! [_ _ summary] (swap! actions conj [:handoff summary])))

(defrecord FakeWorkspace [paths discarded snapshots promoted]
  proto/WorkspaceManager
  (-create-candidate! [_ _ _]
    {:id "c1" :branch "evolve/c1" :worktree "/tmp/c1" :base "main"})
  (-diff [_ _] "diff")
  (-changed-paths [_ _] paths)
  (-clean-ephemeral! [_ _] [])
  (-snapshot-candidate! [_ _ message] (swap! snapshots conj message))
  (-promote-candidate! [_ _ target] (reset! promoted target))
  (-discard-candidate! [_ _] (reset! discarded true)))

(defrecord FakeCandidateRunner [result]
  proto/CandidateRunner
  (-implement! [_ _ _ _] result))

(defrecord FakeEvaluator [results]
  proto/Evaluator
  (-evaluate! [_ _ _ _] results))

(defrecord FakeLedger [records]
  proto/EvolutionLedger
  (-append-event! [_ event] (swap! records conj event))
  (-events [_ run-id] (filterv #(= run-id (:run-id %)) @records)))

(def proposal
  {:id "p1" :title "Improve parser" :objective "Reject bad input"
   :evidence ["fixture fails"] :acceptance ["fixture passes"]})

(def command
  {:status :ok :exit-code 0 :stdout "" :stderr ""
   :duration-ms 1 :truncated? false :network-isolated? true
   :workspace-isolated? true})

(def gates
  [{:id "acceptance" :kind :acceptance :argv ["test"]
    :timeout-ms 100 :network :deny :required? true}])

(def results
  [{:id "acceptance" :kind :acceptance :required? true
    :passed? true :command command}])

(def implementation
  {:status :completed :turns 1 :tool-calls 2 :token-usage 10
   :summary "done"})

(defn- components
  [paths]
  (let [actions (atom [])
        discarded (atom false)
        snapshots (atom [])
        promoted (atom nil)
        records (atom [])
        ticks (atom 0)
        ids (atom 0)]
    [{:board (->FakeBoard actions)
      :workspace-manager (->FakeWorkspace paths discarded snapshots promoted)
      :candidate-runner (->FakeCandidateRunner implementation)
      :evaluator (->FakeEvaluator results)
      :ledger (->FakeLedger records)
      :clock #(swap! ticks inc)
      :id-fn #(str "id-" (swap! ids inc))}
     {:actions actions :discarded discarded
      :snapshots snapshots :promoted promoted :records records}]))

(deftest passing-candidate-is-snapshotted-and-handed-to-review
  (let [[components seen] (components ["src/example.clj"])
        result (supervisor/evolve!
                components
                {:repository-root "/repo" :card-id "011"
                 :proposal proposal :gates gates})]
    (is (= :handoff (:phase result)) (pr-str result))
    (is (= ["evolve: Improve parser"] @(:snapshots seen)))
    (is (some #(= :handoff (first %)) @(:actions seen)))
    (is (= "/repo/card" @(:promoted seen)))
    (is (true? @(:discarded seen)))
    (is (some #(= :candidate-accepted (:type %)) @(:records seen)))))

(deftest protected-change-is-rejected-and-discarded
  (let [[components seen]
        (components ["src/kschltz/agent/evolution/state.clj"])
        result (supervisor/evolve!
                components
                {:repository-root "/repo" :card-id "011"
                 :proposal proposal :gates gates})]
    (is (= :rejected (:phase result)))
    (is (true? @(:discarded seen)))
    (is (empty? @(:snapshots seen)))))

(defrecord FixtureBoard [worktree handed-off]
  proto/Board
  (-context [_ card-id] {:id card-id :worktree worktree})
  (-decision! [_ _ _] {:ok true})
  (-handoff! [_ _ summary] (reset! handed-off summary)))

(defrecord WritingCandidateRunner []
  proto/CandidateRunner
  (-implement! [_ candidate _ _]
    (let [dir (io/file (:worktree candidate) "src")]
      (.mkdirs dir)
      (spit (io/file dir "evolved.txt") "verified\n"))
    implementation))

(defn- temp-dir []
  (str (Files/createTempDirectory
        "lateralus-evolution-e2e-"
        (make-array FileAttribute 0))))

(defn- fixture-command!
  [runner cwd argv]
  (let [result (proto/-run-command!
                runner {:argv argv :cwd cwd :timeout-ms 10000
                        :max-output-bytes 65536 :network :deny})]
    (is (= :ok (:status result)) (pr-str result))
    result))

(deftest disposable-repository-evolution-e2e
  (let [root (temp-dir)
        repo (str (io/file root "repo"))
        candidates (str (io/file root "candidates"))
        _ (.mkdirs (io/file repo))
        runner (process/local-command-runner
                {:allowed-programs #{"git" "/bin/test"}
                 :allowed-roots [root]})
        _ (fixture-command! runner repo ["git" "init" "-b" "main"])
        _ (spit (io/file repo "README.md") "base\n")
        _ (fixture-command! runner repo ["git" "add" "README.md"])
        _ (fixture-command! runner repo
                            ["git" "-c" "user.name=Test"
                             "-c" "user.email=test@localhost"
                             "commit" "-m" "base"])
        _ (fixture-command! runner repo
                            ["git" "checkout" "-b" "kb/test-evolution"])
        handed-off (atom nil)
        audit-store (memory/memory-store)
        result
        (supervisor/evolve!
         {:board (->FixtureBoard repo handed-off)
          :workspace-manager
          (worktree/git-workspace-manager
           {:runner runner :repository-root repo
            :worktree-parent candidates :base-branch "main"})
          :candidate-runner (->WritingCandidateRunner)
          :evaluator (evaluator/command-evaluator runner)
          :ledger (ledger/store-ledger audit-store)}
         {:repository-root repo
          :card-id "fixture"
          :proposal proposal
          :policy {:require-network-isolation? true}
          :gates [{:id "created-file"
                   :kind :acceptance
                   :argv ["/bin/test" "-f" "src/evolved.txt"]
                   :timeout-ms 10000
                   :network :deny
                   :required? true}]})
        main-tree (fixture-command! runner repo
                                    ["git" "ls-tree" "--name-only"
                                     "main" "src/evolved.txt"])]
    (is (= :handoff (:phase result)) (pr-str result))
    (is (= "verified\n" (slurp (io/file repo "src" "evolved.txt"))))
    (is (= "" (:stdout main-tree))
        "the verified commit must not modify main")
    (is (= "evolve/" (subs (get-in @handed-off [:candidate :branch]) 0 7)))
    (is (seq (proto/-events (ledger/store-ledger audit-store)
                            (:id result))))))
