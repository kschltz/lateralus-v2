(ns kschltz.agent.tools.factory.tools-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.factory.protocol :as proto]
            [kschltz.agent.tools.factory.session :as session]
            [kschltz.agent.tools.factory.tools :as tools]
            [kschltz.agent.transitions :as tr]))

(def spec
  {:name "add_two"
   :description "Add two integers"
   :input-schema "[:map [:a :int] [:b :int]]"
   :invoke "(fn [args _ctx] (str (+ (:a args) (:b args))))"})

(deftest control-tools-emit-transitions
  (let [store (session/factory-session {})
        reg (tools/factory-tools-registry store)
        define (get reg "tool_define")
        parsed (json/parse-string (tool/invoke-tool define spec {}) true)]
    (is (= #{"tool_define" "tool_test" "tool_forget"
             "tool_list_runtime" "tool_promote"}
           (set (keys reg))))
    (is (true? (:ok parsed)))
    (is (= "register-runtime-tool" (get-in parsed [:transition :op])))
    (is (tr/valid-transition?
         (update (:transition parsed) :op keyword)))))

(deftest define-accepts-model-shaped-args
  (let [store (session/factory-session {})
        define (get (tools/factory-tools-registry store) "tool_define")
        parsed (json/parse-string
                (tool/invoke-tool
                 define
                 {:name "add_two"
                  :description "Add two integers"
                  :input_schema [:map [:a :int] [:b :int]]
                  :invoke "(fn [args _ctx] (str (+ (:a args) (:b args))))"}
                 {})
                true)]
    (is (true? (:ok parsed)))
    (is (= "[:map [:a :int] [:b :int]]"
           (get-in parsed [:transition :spec :input-schema])))))

(deftest define-accepts-openai-style-schema-alias
  (let [normalized
        (tools/normalize-tool-spec
         {:name "credential_status"
          :description "Classify credential presence"
          :schema {:type :map
                   :properties {:credential {:type :string}}
                   :required [:credential]}
          :invoke "(fn [args] (if (:credential args) \"available\" \"missing\"))"})]
    (is (= "[:map [:credential :string]]" (:input-schema normalized)))
    (is (proto/valid-tool-spec? normalized))))

(deftest define-accepts-gemma-pseudo-schema-string
  (let [normalized
        (tools/normalize-tool-spec
         {:name "credential_status"
          :description "Classify credential presence"
          :input-schema
          "{:type :map :keys {:credential {:type :string :required true}}}"
          :invoke "(fn [args ctx] (if (:credential args) \"available\" \"missing\"))"})]
    (is (= "[:map [:credential :string]]" (:input-schema normalized)))
    (is (proto/valid-tool-spec? normalized))))

(deftest define-accepts-gemma-malli-shorthand
  (let [normalized
        (tools/normalize-tool-spec
         {:name "credential_status"
          :description "Classify credential presence"
          :malli "{:credential {:type :string}}"
          :invoke "(fn [args] (if (:credential args) \"available\" \"missing\"))"})]
    (is (= "[:map [:credential :string]]" (:input-schema normalized)))
    (is (proto/valid-tool-spec? normalized))))

(deftest define-accepts-flattened-json-schema-vector
  (let [normalized
        (tools/normalize-tool-spec
         {:name "credential_status"
          :description "Classify credential presence"
          :input-schema ["map" "handle" "string"]
          :invoke "(fn [args] (if (:handle args) \"available\" \"missing\"))"})]
    (is (= "[:map [:handle :string]]" (:input-schema normalized)))
    (is (proto/valid-tool-spec? normalized))))

(deftest define-accepts-nested-json-schema-vector
  (let [normalized
        (tools/normalize-tool-spec
         {:name "credential_status"
          :description "Classify credential presence"
          :input-schema ["map" ["handle" "string"] ["token" "string"]]
          :invoke "(fn [args] \"ok\")"})]
    (is (= "[:map [:handle :string] [:token :string]]" (:input-schema normalized)))
    (is (proto/valid-tool-spec? normalized))))

(deftest define-accepts-colon-prefixed-json-schema-tokens
  (let [normalized
        (tools/normalize-tool-spec
         {:name "credential_status"
          :description "Classify credential presence"
          :input-schema [":map" [":handle" ":string"]]
          :invoke "(fn [args] (if (:handle args) \"available\" \"missing\"))"})]
    (is (= "[:map [:handle :string]]" (:input-schema normalized)))
    (is (proto/valid-tool-spec? normalized))))

(deftest list-runtime-is-read-only
  (let [store (session/factory-session {})
        t (get (tools/factory-tools-registry store) "tool_list_runtime")
        parsed (json/parse-string (tool/invoke-tool t {} {}) true)]
    (is (true? (:ok parsed)))
    (is (nil? (:transition parsed)))))

(deftest test-tool-records-only-an-exact-passing-result
  (let [store (session/factory-session {})
        _ (proto/-define! store spec {})
        reg (tools/factory-tools-registry store)
        test-tool (get reg "tool_test")
        ctx {:agent/tool-registry
             (merge reg (proto/-registry store))}
        passing (json/parse-string
                 (tool/invoke-tool test-tool
                                   {:name "add_two"
                                    :arguments {:a 1 :b 2}
                                    :expected-output "3"}
                                   ctx)
                 true)
        failing (json/parse-string
                 (tool/invoke-tool test-tool
                                   {:name "add_two"
                                    :arguments {:a 1 :b 2}
                                    :expected-output "4"}
                                   ctx)
                 true)]
    (is (true? (:ok passing)))
    (is (= "3" (:actual passing)))
    (is (= "record-runtime-tool-test" (get-in passing [:transition :op])))
    (is (false? (:ok failing)))
    (is (= "3" (:actual failing)))
    (is (nil? (:transition failing)))))

(deftest test-tool-tolerates-small-model-argument-aliases
  (let [store (session/factory-session {})
        _ (proto/-define! store spec {})
        registry (tools/factory-tools-registry store)
        test-tool (get registry "tool_test")
        parsed (json/parse-string
                (tool/invoke-tool
                 test-tool
                 {:name "add_two"
                  :args {:a 1 :b 2}
                  :expected-output "3"
                  :input-context {}
                  :output-context {}}
                 {:agent/tool-registry
                  (merge registry (proto/-registry store))})
                true)]
    (is (true? (:ok parsed)))
    (is (= "3" (:actual parsed)))
    (is (= "record-runtime-tool-test" (get-in parsed [:transition :op])))))

(deftest promote-tool-preflights-unknown-and-untested-tools
  (let [store (session/factory-session {})
        promote (get (tools/factory-tools-registry store) "tool_promote")
        invoke-promote #(json/parse-string
                         (tool/invoke-tool promote
                                           {:name "add_two"
                                            :target "workspace"}
                                           {})
                         true)
        unknown (invoke-promote)
        _ (proto/-define! store spec {})
        untested (invoke-promote)
        _ (proto/-record-test! store "add_two" (proto/spec-id spec))
        ready (invoke-promote)]
    (is (false? (:ok unknown)))
    (is (= "unknown" (:phase unknown)))
    (is (nil? (:transition unknown)))
    (is (false? (:ok untested)))
    (is (= "needs-test" (:phase untested)))
    (is (nil? (:transition untested)))
    (is (true? (:ok ready)))
    (is (= "promote-runtime-tool" (get-in ready [:transition :op])))))

(deftest promote-tool-accepts-tool-name-alias
  (let [store (session/factory-session {})
        promote (get (tools/factory-tools-registry store) "tool_promote")
        parsed (json/parse-string
                (tool/invoke-tool promote
                                  {:tool "not_defined"
                                   :target "workspace"}
                                  {})
                true)]
    (is (false? (:ok parsed)))
    (is (= "not_defined" (:tool-name parsed)))
    (is (= "unknown" (:phase parsed)))))
