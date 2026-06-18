(ns kschltz.agent.tools.web-search.policy-test
  "Tests for the web search policy model."
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.tools.web-search.policy :as policy]))

(deftest policy-passes-through-when-nil
  (is (= [{:title "A" :snippet "s"}]
         (policy/apply-policy nil [{:title "A" :snippet "s"}]))))
