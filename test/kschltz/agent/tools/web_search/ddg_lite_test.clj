(ns kschltz.agent.tools.web-search.ddg-lite-test
  "Tests for the DuckDuckGo Lite web search provider."
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.tools.web-search.ddg-lite :as ddg]))

(deftest provider-can-be-constructed
  (is (some? (ddg/provider))))

(deftest provider-accepts-config
  (is (some? (ddg/provider {:timeout-ms 5000}))))

(deftest extract-vqd-pulls-token-from-html
  (let [html "... vqd='123-456' ..."]
    (is (= "123-456" (#'ddg/extract-vqd html))))
  (is (nil? (#'ddg/extract-vqd "no token here"))))

(deftest parse-json-results-extracts-results
  (let [body "{\"results\":[{\"u\":\"https://example.com/a\",\"t\":\"Example A\",\"a\":\"snippet A\"},{\"u\":\"https://example.com/b\",\"t\":\"Example B\",\"a\":\"snippet B\"}]}"
        results (#'ddg/parse-json-results body)]
    (is (= 2 (count results)))
    (is (= "https://example.com/a" (:url (first results))))
    (is (= "Example A" (:title (first results))))
    (is (= "snippet A" (:snippet (first results))))))

(deftest parse-json-results-skips-ads-and-disambiguation
  (let [body "{\"results\":[{\"u\":\"https://example.com/good\",\"t\":\"Good\",\"a\":\"ok\"},{\"u\":\"https://ad\",\"t\":\"ad\"},{\"u\":\"\",\"t\":\"Empty\"}]}"
        results (#'ddg/parse-json-results body)]
    (is (= 1 (count results)))
    (is (= "Good" (:title (first results))))))

(deftest parse-json-results-decodes-bounce-urls
  (let [body "{\"results\":[{\"u\":\"/l/?uddg=https%3A%2F%2Fexample.com%2Fbounce\",\"t\":\"Bounce\",\"a\":\"bounced\"}]}"
        results (#'ddg/parse-json-results body)]
    (is (= 1 (count results)))
    (is (= "https://example.com/bounce" (:url (first results))))))

(deftest parse-lite-results-extracts-real-snippets
  "DDG Lite renders each result as three rows: title link, snippet, URL."
  (let [html "<table>
                <tr><td>1.</td><td><a href=\"https://example.com/a\">Example A</a></td></tr>
                <tr><td>This is the real snippet for A.</td></tr>
                <tr><td>example.com/a</td></tr>
                <tr><td>2.</td><td><a href=\"https://example.com/b\">Example B</a></td></tr>
                <tr><td>This is the real snippet for B.</td></tr>
                <tr><td>example.com/b</td></tr>
              </table>"
        results (#'ddg/parse-lite-results html)]
    (is (= 2 (count results)))
    (is (= "This is the real snippet for A." (:snippet (first results))))
    (is (= "Example A" (:title (first results))))
    (is (= "https://example.com/a" (:url (first results))))))

(deftest lite-landing-page-detected
  (is (#'ddg/lite-landing-page? "<html><title>DuckDuckGo</title></html>"))
  (is (not (#'ddg/lite-landing-page? "<div class=\"result\">result here</div>"))))
