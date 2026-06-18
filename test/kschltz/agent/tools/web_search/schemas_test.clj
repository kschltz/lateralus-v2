(ns kschltz.agent.tools.web-search.schemas-test
  "Tests for web search Malli schemas."
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.tools.web-search.schemas :as schemas]
            [malli.core :as m]))

(deftest input-schema-exists
  (is (some? schemas/WebSearchInput)))

(deftest input-schema-validates-query
  (is (nil? (m/explain schemas/WebSearchInput {:query "Clojure"})))
  (is (some? (m/explain schemas/WebSearchInput {}))))

(deftest output-schema-exists
  (is (some? schemas/WebSearchOutput)))
