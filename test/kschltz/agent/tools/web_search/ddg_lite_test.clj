(ns kschltz.agent.tools.web-search.ddg-lite-test
  "Tests for the DuckDuckGo Lite web search provider."
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.tools.web-search.ddg-lite :as ddg]))

(deftest provider-can-be-constructed
  (is (some? (ddg/provider))))

(deftest provider-accepts-config
  (is (some? (ddg/provider {:timeout-ms 5000}))))

(deftest parse-results-extracts-real-snippets
  "DDG Lite renders each result as three rows: title link, snippet, URL."
  (let [html "<table>
                <tr><td>1.</td><td><a href=\"https://example.com/a\">Example A</a></td></tr>
                <tr><td>This is the real snippet for A.</td></tr>
                <tr><td>example.com/a</td></tr>
                <tr><td>2.</td><td><a href=\"https://example.com/b\">Example B</a></td></tr>
                <tr><td>This is the real snippet for B.</td></tr>
                <tr><td>example.com/b</td></tr>
              </table>"
        results (#'ddg/parse-results html)]
    (is (= 2 (count results)))
    (is (= "This is the real snippet for A." (:snippet (first results))))
    (is (= "Example A" (:title (first results))))
    (is (= "https://example.com/a" (:url (first results))))))
