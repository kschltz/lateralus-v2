(ns kschltz.agent.tools.mcp.json-schema-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.tools.mcp.json-schema :as json-schema]))

(deftest object-schema-preserves-required-and-optional-fields
  (is (= [:map
          [:name :string]
          [:count {:optional true} :int]]
         (json-schema/json-schema->malli
          {"type" "object"
           "properties" {"name" {"type" "string"}
                         "count" {"type" "integer"}}
           "required" ["name"]}))))

(deftest primitive-and-unknown-schemas-degrade-safely
  (is (= :boolean
         (json-schema/json-schema->malli {:type "boolean"})))
  (is (= [:map]
         (json-schema/json-schema->malli nil))))
