(ns kschltz.agent.evolution.protocol-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.evolution.protocol :as proto]))

(defrecord FakeRunner []
  proto/CommandRunner
  (-run-command! [_ _] {:status :ok}))

(deftest capability-predicates-are-explicit
  (is (proto/command-runner? (->FakeRunner)))
  (is (not (proto/board? (->FakeRunner))))
  (is (not (proto/evolution-ledger? nil))))
