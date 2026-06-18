(ns kschltz.agent.tools.web-search.protocol-test
  "Tests for the web search provider protocol."
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.tools.web-search.protocol :as protocol]))

(deftest protocol-has-required-functions
  (is (some? protocol/WebSearchProvider))
  (is (fn? protocol/-search))
  (is (fn? protocol/-fetch-page)))
