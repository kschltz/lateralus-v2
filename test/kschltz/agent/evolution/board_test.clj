(ns kschltz.agent.evolution.board-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.evolution.board :as board]
            [kschltz.agent.evolution.protocol :as proto]))

(defrecord RecordingRunner [commands]
  proto/CommandRunner
  (-run-command! [_ command]
    (swap! commands conj command)
    {:status :ok :exit-code 0
     :stdout (if (= "show" (second (:argv command)))
               (json/generate-string
                {:card {:id (nth (:argv command) 2)
                        :lane :in-progress}})
               "ok")
     :stderr ""
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

(deftest handoff-is-idempotent-when-card-is-already-in-review
  (let [commands (atom [])
        runner
        (reify proto/CommandRunner
          (-run-command! [_ command]
            (swap! commands conj command)
            {:status :ok :exit-code 0
             :stdout (json/generate-string
                      {:card {:id "011" :lane :review}})
             :stderr "" :duration-ms 1 :truncated? false
             :network-isolated? true :workspace-isolated? false}))
        adapter (board/kb-board {:runner runner
                                 :repository-root "/tmp"
                                 :agent "test"})
        result (proto/-handoff!
                adapter "011"
                {:run-id "run-1" :changed-paths ["src/a.clj"]
                 :candidate {:branch "evolve/run-1"}})]
    (is (:already-handed-off? result))
    (is (= 1 (count @commands)))
    (is (not-any? #(= "advance" (second (:argv %))) @commands))))
