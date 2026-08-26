(ns kschltz.agent.loop.summary-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.loop.summary :as summary]))

(deftest strip-drops-tool-calls-and-rewrites-tool-role
  (let [msgs [{:role "system" :content "sys"}
              {:role "user" :content "hi"}
              {:role "assistant" :content "" :tool_calls [{:id "1"}]}
              {:role "tool" :name "echo" :content "ok"}
              {:role "assistant" :content "keep me" :tool_calls [{:id "2"}]}]
        out (summary/strip-tool-call-scaffold msgs)]
    (is (not-any? #(seq (:tool_calls %)) out))
    (is (not-any? #(= "tool" (:role %)) out))
    (is (some #(str/includes? (str (:content %)) "Tool echo returned:") out))
    (is (some #(= "keep me" (:content %)) out))))

(deftest synthesize-from-results-builds-prose
  (let [s (summary/synthesize-from-results
           [{:call {:function {:name "echo"}} :result "hello"}])]
    (is (str/includes? s "echo"))
    (is (str/includes? s "hello"))
    (is (str/includes? s "did not produce a final"))))

(deftest synthesize-nil-when-empty
  (is (nil? (summary/synthesize-from-results [])))
  (is (nil? (summary/synthesize-from-results nil))))

(deftest condensed-messages-are-minimal
  (let [msgs (summary/condensed-messages
              {:agent/state {:agent/system-message "SYS"}
               :exchange/user-text "do it"
               :agent/all-tool-results [{:call {:function {:name "echo"}}
                                         :result "ok"}]})]
    (is (= "SYS" (:content (first msgs))))
    (is (= "user" (:role (second msgs))))
    (is (not-any? #(seq (:tool_calls %)) msgs))
    (is (some #(str/includes? (str (:content %)) "echo") msgs))))

(deftest coerce-clears-empty-content-tool-calls
  (let [ctx {:llm/request {:tool-choice "none"}
             :tool/calls [{:id "x"}]
             :exchange/response ""}
        out (summary/coerce-malformed-summary ctx)]
    (is (true? (:agent/malformed-summary-tool-calls? out)))
    (is (empty? (:tool/calls out)))))

(deftest coerce-leaves-real-answers-alone
  (let [ctx {:llm/request {:tool-choice "none"}
             :tool/calls []
             :exchange/response "done"}]
    (is (= ctx (summary/coerce-malformed-summary ctx)))))

(deftest apply-summary-request-condenses-on-second-attempt
  (let [ctx {:agent/summary-attempts 2
             :agent/state {:agent/system-message "SYS"}
             :exchange/user-text "go"
             :agent/all-tool-results [{:call {:function {:name "echo"}}
                                       :result "ok"}]
             :llm/request {:messages [{:role "assistant" :content ""
                                       :tool_calls [{:id "1"}]}]
                           :tools [{:name "echo"}]}}
        out (summary/apply-summary-request ctx)
        req (:llm/request out)]
    (is (nil? (:tools req)))
    (is (= "none" (:tool-choice req)))
    (is (not-any? #(seq (:tool_calls %)) (:messages req)))
    (is (some #(str/includes? (str (:content %)) "digest") (:messages req)))))

(deftest apply-fallback-fills-blank-response
  (let [out (summary/apply-fallback
             {:exchange/response ""
              :agent/all-tool-results [{:call {:function {:name "echo"}}
                                        :result "ok"}]})]
    (is (true? (:agent/summary-failed? out)))
    (is (true? (:agent/summary-synthesized? out)))
    (is (str/includes? (:exchange/response out) "echo"))))
