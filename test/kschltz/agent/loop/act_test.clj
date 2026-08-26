(ns kschltz.agent.loop.act-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.loop.act :as act]))

(def ^:private echo-reg {"echo" :tool})

(deftest planning-only-detects-announce-then-yield
  (testing "first-person future + act verb with tools available"
    (is (act/planning-only?
         "I'll implement the echo helper and then call it."
         {:tool-calls [] :registry echo-reg :loop-opts {}})))
  (testing "plan marker without I'll"
    (is (act/planning-only?
         "Here's the plan:\n1. read the file\n2. patch it"
         {:tool-calls [] :registry echo-reg})))
  (testing "let me + build"
    (is (act/planning-only?
         "Let me build a small HTML preview next."
         {:tool-calls [] :registry echo-reg}))))

(deftest planning-only-rejects-real-answers-and-polite-closers
  (is (not (act/planning-only?
            "The capital of France is Paris."
            {:tool-calls [] :registry echo-reg})))
  (is (not (act/planning-only?
            "Let me know if you want changes."
            {:tool-calls [] :registry echo-reg})))
  (is (not (act/planning-only?
            "I'll implement that next."
            {:tool-calls [{:id "t1"}] :registry echo-reg}))
      "current-turn tool_calls means the model already acted")
  (is (not (act/planning-only?
            "I'll implement that next."
            {:tool-calls [] :registry {}}))
      "no tools registered — nothing to continue into")
  (is (not (act/planning-only?
            "I'll implement that next."
            {:tool-calls [] :registry echo-reg :loop-opts {:act-nudge? false}}))))

(deftest apply-nudge-merges-when-last-message-is-assistant
  (let [ctx {:exchange/response "I'll call echo."
             :llm/request {:messages [{:role "user" :content "do it"}
                                      {:role "assistant" :content "thinking aloud"}]
                           :tools [{:type "function"}]}}
        out (act/apply-nudge ctx)
        msgs (get-in out [:llm/request :messages])
        asst (filter #(= "assistant" (:role %)) msgs)]
    (is (= 1 (count asst)))
    (is (str/includes? (:content (first asst)) "thinking aloud"))
    (is (str/includes? (:content (first asst)) "I'll call echo."))))

(deftest apply-nudge-appends-plan-and-keeps-tools
  (let [ctx {:exchange/response "I'll call echo."
             :llm/request {:messages [{:role "user" :content "do it"}]
                           :tools [{:type "function"}]}}
        out (act/apply-nudge ctx)
        msgs (get-in out [:llm/request :messages])]
    (is (true? (:agent/act-nudged? out)))
    (is (= {:role "assistant" :content "I'll call echo."} (nth msgs 1)))
    (is (= act/nudge-content (:content (last msgs))))
    (is (seq (get-in out [:llm/request :tools]))
        "follow-up must keep tools so the model can implement")))

(deftest merge-system-guidance-shapes
  (is (= act/system-guidance (act/merge-system-guidance nil)))
  (is (re-find #"Do not announce" (act/merge-system-guidance "base")))
  (is (= ["prior" act/system-guidance] (act/merge-system-guidance ["prior"]))))
