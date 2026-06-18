(ns kschltz.agent.tools.web-search.searxng-test
  "Tests for the SearXNG web search provider."
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.tools.web-search.searxng :as searxng]))

(deftest provider-can-be-constructed
  (is (some? (searxng/provider))))

(deftest provider-accepts-base-url
  (is (some? (searxng/provider {:base-url "http://localhost:8888"}))))
