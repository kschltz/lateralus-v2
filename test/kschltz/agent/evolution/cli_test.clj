(ns kschltz.agent.evolution.cli-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.evolution.cli :as cli]
            [kschltz.agent.evolution.ledger :as ledger]
            [kschltz.agent.store.duckdb :as duckdb]
            [kschltz.agent.store.protocol :as store])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

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

(deftest default-gate-profile-is-explicitly-selectable
  (is (= #{"lint" "fast-tests"}
         (set (map :id ((deref #'cli/default-gates) nil)))))
  (is (= ["lint"]
         (mapv :id ((deref #'cli/default-gates)
                    {:lint? true :fast-tests? false}))))
  (is (empty? ((deref #'cli/default-gates)
               {:lint? false :fast-tests? false}))))

(deftest inspect-run-reads-the-durable-audit-ledger
  (let [root (str (Files/createTempDirectory
                   "evolution-inspect-"
                   (make-array FileAttribute 0)))
        path (str root "/ledger.duckdb")
        engine (duckdb/duckdb-store {:path path})
        event {:id "event-1" :run-id "run-1" :type :run-started
               :phase :observe :ts 1 :payload {:proof true}}]
    (try
      (ledger/append-event! engine event)
      (finally
        (store/-close engine)))
    (is (= [event]
           (cli/inspect-run! {:inspect-run "run-1"
                              :repo root :ledger path})))))
