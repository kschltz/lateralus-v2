(ns kschltz.agent.tool-test
  "Tests for the Tool protocol and Malli-instrumented invocation."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tool :as tool]))

(deftest tool?-recognizes-tool-instances
  (testing "tool? returns true for reifies implementing Tool"
    (let [t (reify tool/Tool
              (-name [_] "test/tool")
              (-description [_] "desc")
              (-input-schema [_] [:map])
              (-output-schema [_] :string)
              (-invoke [_ _] "ok"))]
      (is (tool/tool? t))
      (is (not (tool/tool? {})))
      (is (not (tool/tool? nil))))))

(deftest invoke-tool-validates-input
  (testing "invoke-tool returns an error string for invalid input"
    (let [t (reify tool/Tool
              (-name [_] "echo")
              (-description [_] "echoes x")
              (-input-schema [_] [:map [:x :int]])
              (-output-schema [_] :string)
              (-invoke [_ args] (str (:x args))))]
      (is (str/includes? (tool/invoke-tool t {:x "not-int"}) "input")
          "invalid input produces a validation error"))))

(deftest invoke-tool-returns-result-when-valid
  (testing "invoke-tool returns the tool result when input and output are valid"
    (let [t (reify tool/Tool
              (-name [_] "echo")
              (-description [_] "echoes x")
              (-input-schema [_] [:map [:x :int]])
              (-output-schema [_] :string)
              (-invoke [_ args] (str (:x args))))]
      (is (= "7" (tool/invoke-tool t {:x 7}))))))

(deftest invoke-tool-validates-output
  (testing "invoke-tool returns an error string for non-string output"
    (let [t (reify tool/Tool
              (-name [_] "bad")
              (-description [_] "returns a number")
              (-input-schema [_] [:map])
              (-output-schema [_] :string)
              (-invoke [_ _] 42))]
      (is (str/includes? (tool/invoke-tool t {}) "not a string")
          "non-string result is rejected"))))

(deftest invoke-tool-catches-execution-exceptions
  (testing "tool exceptions become model-visible error strings"
    (let [t (reify tool/Tool
              (-name [_] "boom")
              (-description [_] "always throws")
              (-input-schema [_] [:map])
              (-output-schema [_] :string)
              (-invoke [_ _] (throw (ex-info "explosion" {}))))]
      (is (str/includes? (tool/invoke-tool t {}) "Tool execution error"))
      (is (str/includes? (tool/invoke-tool t {}) "explosion")))))

(deftest tool-definition-shape
  (testing "tool-definition produces OpenAI function-tool shape"
    (let [t (reify tool/Tool
              (-name [_] "calc/add")
              (-description [_] "adds numbers")
              (-input-schema [_] [:map [:a :int] [:b :int]])
              (-output-schema [_] :string)
              (-invoke [_ _] ""))
          def (tool/tool-definition t)]
      (is (= "function" (:type def)))
      (is (= "calc/add" (get-in def [:function :name])))
      (is (= "adds numbers" (get-in def [:function :description])))
      (is (map? (get-in def [:function :parameters]))))))

(deftest execute-tools-with-empty-registry
  (testing "execute-tools returns :not-implemented for unknown tools"
    (let [calls [{:id "1" :type "function" :function {:name "unknown" :arguments "{}"}}]
          results (tool/execute-tools {} calls)]
      (is (= 1 (count results)))
      (is (= :not-implemented (-> results first :result))))))

(deftest execute-tools-invokes-registered-tool
  (testing "execute-tools runs a registered tool and preserves call id"
    (let [t (reify tool/Tool
              (-name [_] "echo")
              (-description [_] "echo")
              (-input-schema [_] [:map [:msg :string]])
              (-output-schema [_] :string)
              (-invoke [_ args] (:msg args)))
          calls [{:id "42" :type "function" :function {:name "echo" :arguments "{\"msg\":\"hi\"}"}}]
          results (tool/execute-tools {"echo" t} calls)]
      (is (= 1 (count results)))
      (is (= "42" (-> results first :call :id)))
      (is (= "hi" (-> results first :result))))))
