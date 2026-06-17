(ns kschltz.agent.cli.spinner-test
  "Tests for the CLI spinner.

   The spinner is a tiny UI helper: start! launches a daemon thread
   that prints an in-place animation, and stop! clears it. We verify
   the happy path and that stop! is idempotent."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.cli.spinner :as spinner]))

(deftest spinner-lifecycle
  (testing "spinner starts, animates briefly, and stops cleanly"
    (let [out (java.io.StringWriter.)
          pw  (java.io.PrintWriter. out true)
          s   (spinner/start! pw "thinking")]
      (is (some? s))
      (Thread/sleep 250)
      (spinner/stop! s)
      (let [rendered (str out)]
        (is (clojure.string/includes? rendered "thinking"))
        (is (clojure.string/includes? rendered "\r"))))))

(deftest spinner-stop-is-idempotent
  (testing "calling stop! more than once does not throw"
    (let [out (java.io.StringWriter.)
          pw  (java.io.PrintWriter. out true)
          s   (spinner/start! pw "waiting")]
      (spinner/stop! s)
      (spinner/stop! s)
      (is true))))
