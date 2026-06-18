(ns kschltz.agent.tools.web-search.ddg-lite-test
  "Tests for the DuckDuckGo Lite web search provider."
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.tools.web-search.ddg-lite :as ddg]))

(deftest provider-can-be-constructed
  (is (some? (ddg/provider))))

(deftest provider-accepts-config
  (is (some? (ddg/provider {:timeout-ms 5000}))))
