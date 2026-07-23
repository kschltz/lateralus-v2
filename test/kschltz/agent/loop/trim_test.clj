(ns kschltz.agent.loop.trim-test
  "Tests for in-exchange message trimming (`kschltz.agent.loop.trim`)."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.loop.trim :as trim]))

(defn- msg [role content] {:role role :content content})
(defn- tool-msg [id] {:role "tool" :tool_call_id id :content "r"})
(defn- asst-calls [ids]
  {:role "assistant" :content ""
   :tool_calls (mapv #(hash-map :id % :type "function"
                                :function {:name "echo" :arguments "{}"})
                     ids)})

(deftest trim-in-flight-drops-orphan-tool-results
  (testing "a 45-msg vector whose cut lands inside a tool cycle produces a
            trimmed result that does NOT open with an orphan :role \"tool\"
            message, and the trailing complete [assistant(tool_calls),tool,tool]
            cycle survives intact"
    (let [filler (mapv #(msg "assistant" (str "turn " %)) (range 9 42))
          msgs (into [{:role "system" :content "sys"}
                      (msg "user" "do it")
                      (asst-calls ["tc0"])
                      (tool-msg "tc0")
                      (msg "assistant" "text")
                      (asst-calls ["tc1"])
                      (tool-msg "tc1")
                      (tool-msg "tc1")]
                     (into filler
                           [(asst-calls ["tc2" "tc3"])
                            (tool-msg "tc2")
                            (tool-msg "tc3")]))
          trimmed (trim/trim-in-flight-messages msgs)
          roles (mapv :role trimmed)]
      (is (<= (count trimmed) trim/max-in-flight-entries)
          "trimmed count respects the in-flight cap")
      (is (not= "tool" (nth roles 2 nil))
          "the first message after the [system,user] head is NOT an orphan tool")
      (is (not (some #(and (= "tool" (:role %))
                           (= "tc1" (:tool_call_id %)))
                     trimmed))
          "the orphaned tc1 tool result was dropped")
      (is (= ["assistant" "tool" "tool"]
             (subvec roles (- (count roles) 3)))
          "the trailing complete cycle survives")
      (is (= ["tc2" "tc3"]
             (mapv :id (:tool_calls (nth trimmed (- (count trimmed) 3)))))
          "the trailing assistant tool_calls carry tc2 and tc3"))))
