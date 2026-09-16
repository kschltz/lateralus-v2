(ns kschltz.agent.evolution.cli-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.evolution.cli :as cli]))

(deftest parses-required-local-run-options
  (let [{:keys [options errors summary]}
        (cli/parse-args ["--proposal" "proposal.edn"
                         "--card" "011"
                         "--repo" "/tmp/repo"])]
    (is (empty? errors))
    (is (= "proposal.edn" (:proposal options)))
    (is (= "011" (:card options)))
    (is (re-find #"never pushes, merges, deploys"
                 (cli/help-text summary)))))

(deftest validates-gate-programs-before-run-setup
  (let [gate {:id "acceptance"
              :kind :acceptance
              :argv ["java" "-version"]
              :timeout-ms 1000
              :network :deny
              :required? true}]
    (is (= [gate] (cli/validate-gates! [gate])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"not allowlisted"
         (cli/validate-gates! [(assoc gate :argv ["git" "status"])])))))
