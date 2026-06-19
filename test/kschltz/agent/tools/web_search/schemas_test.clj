(ns kschltz.agent.tools.web-search.schemas-test
  "Tests for web search Malli schemas."
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.tools.web-search.schemas :as schemas]
            [malli.core :as m]
            [malli.json-schema :as json-schema]))

(deftest input-schema-exists
  (is (some? schemas/WebSearchInput)))

(deftest input-schema-validates-query
  (is (nil? (m/explain schemas/WebSearchInput {:query "Clojure"})))
  (is (some? (m/explain schemas/WebSearchInput {})))
  (is (some? (m/explain schemas/WebSearchInput {:query ""}))))

(deftest query-json-schema-is-simple-and-serializable
  "Local models are confused by JSON Schema allOf and empty validator
   objects. The generated web_search parameters must be a flat object with
   a plain string query property."
  (let [params (json-schema/transform schemas/WebSearchInput)]
    (is (= "object" (:type params)))
    (is (= "string" (get-in params [:properties :query :type])))
    (is (integer? (get-in params [:properties :query :minLength])))
    (is (nil? (get-in params [:properties :query :allOf])))))

(deftest output-schema-exists
  (is (some? schemas/WebSearchOutput)))
