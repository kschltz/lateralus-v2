(ns kschltz.agent.evolution.schemas-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.evolution.schemas :as schemas]))

(deftest proposal-is-closed-and-evidence-backed
  (let [proposal {:id "p1"
                  :title "Improve parser"
                  :objective "Reject malformed input"
                  :evidence ["A failing fixture"]
                  :acceptance ["Fixture passes"]}]
    (is (schemas/valid? schemas/Proposal proposal))
    (is (not (schemas/valid? schemas/Proposal (assoc proposal :unknown true))))
    (is (not (schemas/valid? schemas/Proposal
                             (assoc proposal :evidence []))))))
