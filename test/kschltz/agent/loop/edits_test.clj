(ns kschltz.agent.loop.edits-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.loop.edits :as edits]))

(deftest path->agent-ns-maps-src-paths
  (is (= "kschltz.agent.loop"
         (edits/path->agent-ns "src/kschltz/agent/loop.clj")))
  (is (= "kschltz.agent.loop"
         (edits/path->agent-ns "/Users/x/projects/lateralus-v2/src/kschltz/agent/loop.clj")))
  (is (nil? (edits/path->agent-ns "notebooks/demo.clj"))))

(deftest merge-edited-persists-state-delta
  (let [results [{:result (json/generate-string
                           {:path "src/kschltz/agent/loop.clj"
                            :updated true})}]
        out (edits/merge-edited {:agent/state {}} results)]
    (is (= ["kschltz.agent.loop"] (:agent/edited-namespaces out)))
    (is (= ["kschltz.agent.loop"]
           (get-in out [:agent/state-delta :agent/edited-namespaces])))))
