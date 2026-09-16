(ns kschltz.agent.evolution.board-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.evolution.board :as board]
            [kschltz.agent.evolution.protocol :as proto]))

(defrecord RecordingRunner [commands]
  proto/CommandRunner
  (-run-command! [_ command]
    (swap! commands conj command)
    {:status :ok :exit-code 0 :stdout "ok" :stderr ""
     :duration-ms 1 :truncated? false :network-isolated? true
     :workspace-isolated? false}))

(deftest handoff-advances-but-never-merges-or-completes
  (let [commands (atom [])
        adapter (board/kb-board
                 {:runner (->RecordingRunner commands)
                  :repository-root "/tmp"
                  :agent "test"})]
    (proto/-handoff!
     adapter "011"
     {:run-id "run-1"
      :changed-paths ["src/a.clj"]
      :candidate {:branch "evolve/run-1"}})
    (let [argvs (mapv :argv @commands)]
      (is (some #(= ["kb" "advance" "011" "--agent" "test"] %) argvs))
      (is (not-any? #(some #{"merge" "push" "done"} %) argvs)))))
