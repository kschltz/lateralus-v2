(ns kschltz.agent.tool-test
  "Tests for the Tool protocol and Malli-instrumented invocation."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tool :as tool]))

(deftest tool?-recognizes-tool-instances
  (testing "tool? returns true for reifies implementing Tool"
    (let [t (reify tool/Tool
              (-name [_] "test_tool")
              (-description [_] "desc")
              (-input-schema [_] [:map])
              (-output-schema [_] :string)
              (-invoke [_ _ _] "ok"))]
      (is (tool/tool? t))
      (is (not (tool/tool? {})))
      (is (not (tool/tool? nil))))))

(deftest portable-tool-name-validation
  (testing "portable names use conservative snake_case"
    (is (tool/portable-tool-name? "file_read"))
    (is (tool/portable-tool-name? "tool2"))
    (is (not (tool/portable-tool-name? "file/read")))
    (is (not (tool/portable-tool-name? "file-read")))
    (is (not (tool/portable-tool-name? "2file_read")))
    (is (not (tool/portable-tool-name? (apply str (repeat 65 "a")))))))

(deftest invoke-tool-validates-input
  (testing "invoke-tool returns an error string for invalid input"
    (let [t (reify tool/Tool
              (-name [_] "echo")
              (-description [_] "echoes x")
              (-input-schema [_] [:map [:x :int]])
              (-output-schema [_] :string)
              (-invoke [_ args _] (str (:x args))))]
      (is (str/includes? (tool/invoke-tool t {:x "not-int"} {}) "input")
          "invalid input produces a validation error"))))

(deftest invoke-tool-returns-result-when-valid
  (testing "invoke-tool returns the tool result when input and output are valid"
    (let [t (reify tool/Tool
              (-name [_] "echo")
              (-description [_] "echoes x")
              (-input-schema [_] [:map [:x :int]])
              (-output-schema [_] :string)
              (-invoke [_ args _] (str (:x args))))]
      (is (= "7" (tool/invoke-tool t {:x 7} {}))))))

(deftest invoke-tool-validates-output
  (testing "invoke-tool returns an error string for non-string output"
    (let [t (reify tool/Tool
              (-name [_] "bad")
              (-description [_] "returns a number")
              (-input-schema [_] [:map])
              (-output-schema [_] :string)
              (-invoke [_ _ _] 42))]
      (is (str/includes? (tool/invoke-tool t {} {}) "not a string")
          "non-string result is rejected"))))

(deftest invoke-tool-catches-execution-exceptions
  (testing "tool exceptions become model-visible error strings"
    (let [t (reify tool/Tool
              (-name [_] "boom")
              (-description [_] "always throws")
              (-input-schema [_] [:map])
              (-output-schema [_] :string)
              (-invoke [_ _ _] (throw (ex-info "explosion" {}))))]
      (is (str/includes? (tool/invoke-tool t {} {}) "Tool execution error"))
      (is (str/includes? (tool/invoke-tool t {} {}) "explosion")))))

(deftest tool-definition-shape
  (testing "tool-definition produces OpenAI function-tool shape"
    (let [t (reify tool/Tool
              (-name [_] "calc_add")
              (-description [_] "adds numbers")
              (-input-schema [_] [:map [:a :int] [:b :int]])
              (-output-schema [_] :string)
              (-invoke [_ _ _] ""))
          def (tool/tool-definition t)]
      (is (= "function" (:type def)))
      (is (= "calc_add" (get-in def [:function :name])))
      (is (= "adds numbers" (get-in def [:function :description])))
      (is (map? (get-in def [:function :parameters]))))))

(deftest regex-tool-definition-is-json-serializable
  (testing "Malli regex schemas retain validation and emit JSON Schema pattern strings"
    (let [pattern #"^allowed(?:\.[a-z]+)?$"
          t (reify tool/Tool
              (-name [_] "regex_input")
              (-description [_] "accepts a constrained name")
              (-input-schema [_] [:map [:name [:re pattern]]])
              (-output-schema [_] :string)
              (-invoke [_ args _] (:name args)))
          definition (tool/tool-definition t)]
      (is (= (.pattern pattern)
             (get-in definition [:function :parameters :properties :name :pattern])))
      (is (string? (json/generate-string definition)))
      (is (string? (json/generate-string
                    (tool/json-safe {:pattern pattern}))))
      (is (string? (get-in (tool/json-safe {:pattern pattern}) [:pattern])))
      (is (= "allowed.value" (tool/invoke-tool t {:name "allowed.value"} {})))
      (is (str/includes? (tool/invoke-tool t {:name "forbidden"} {})
                         "input validation failed")))))

(deftest compact-definition-shortens-description-and-params
  (let [t (reify tool/Tool
            (-name [_] "calc_add")
            (-description [_] "Adds two integers. Also documents a long recovery path the local model does not need in the schema.")
            (-input-schema [_] [:map [:a :int] [:b {:optional true} :int]])
            (-output-schema [_] :string)
            (-invoke [_ _ _] ""))
        full (tool/tool-definition t)
        compact (tool/compact-definition t)]
    (is (= "calc_add" (get-in compact [:function :name])))
    (is (< (count (get-in compact [:function :description]))
           (count (get-in full [:function :description]))))
    (is (str/starts-with? (get-in compact [:function :description]) "Adds two integers"))
    (is (contains? (get-in compact [:function :parameters :properties]) :a))
    (is (not (contains? (get-in compact [:function :parameters :properties :a]) :description)))))

(deftest tool-definition-rejects-non-portable-name
  (let [t (reify tool/Tool
            (-name [_] "calc/add")
            (-description [_] "invalid name")
            (-input-schema [_] [:map])
            (-output-schema [_] :string)
            (-invoke [_ _ _] ""))]
    (is (thrown? clojure.lang.ExceptionInfo (tool/tool-definition t)))))

(deftest execute-tools-with-empty-registry
  (testing "execute-tools returns a human-readable error for unknown tools"
    (let [calls [{:id "1" :type "function" :function {:name "unknown" :arguments "{}"}}]
          results (tool/execute-tools {} {} calls)]
      (is (= 1 (count results)))
      (is (str/includes? (-> results first :result) "not available"))
      (is (str/includes? (-> results first :result) "unknown")))))

(deftest resolve-tool-trims-and-matches-unique-case
  (let [t (reify tool/Tool
            (-name [_] "clojure_eval")
            (-description [_] "eval")
            (-input-schema [_] [:map])
            (-output-schema [_] :string)
            (-invoke [_ _ _] "ok"))]
    (is (identical? t (tool/resolve-tool {"clojure_eval" t} " clojure_eval ")))
    (is (identical? t (tool/resolve-tool {"clojure_eval" t} "Clojure_Eval")))
    (is (nil? (tool/resolve-tool {"clojure_eval" t} "missing")))))

(deftest execute-tools-invokes-registered-tool
  (testing "execute-tools runs a registered tool and preserves call id"
    (let [t (reify tool/Tool
              (-name [_] "echo")
              (-description [_] "echo")
              (-input-schema [_] [:map [:msg :string]])
              (-output-schema [_] :string)
              (-invoke [_ args _] (:msg args)))
          calls [{:id "42" :type "function" :function {:name "echo" :arguments "{\"msg\":\"hi\"}"}}]
          results (tool/execute-tools {"echo" t} {} calls)]
      (is (= 1 (count results)))
      (is (= "42" (-> results first :call :id)))
      (is (= "hi" (-> results first :result))))))

(deftest invoke-tool-validation-error-names-tool-and-field
  (testing "audit 2026-07 rec #7: a validation error names the tool and the
            failing key path so the model can fix the call, not just learn
            that 'something' failed"
    (let [t (reify tool/Tool
              (-name [_] "add_lib")
              (-description [_] "adds a lib")
              (-input-schema [_] [:map [:lib [:string {:min 1}]]])
              (-output-schema [_] :string)
              (-invoke [_ _ _] "ok"))
          err (tool/invoke-tool t {:lib ""} {})]
      (is (str/includes? err "add_lib") "names the tool")
      (is (str/includes? err "input validation failed") "phase is input")
      (is (str/includes? err ":lib") "humanized key path is present"))))

(deftest invoke-tool-validation-error-humanizes-disallowed-keys
  (testing "closed input maps tell the model which extra keys to drop"
    (let [t (reify tool/Tool
              (-name [_] "status")
              (-description [_] "status")
              (-input-schema [_] [:map {:closed true}])
              (-output-schema [_] :string)
              (-invoke [_ _ _] "ok"))
          err (tool/invoke-tool t {:name "x" :page 1} {})]
      (is (str/includes? err "status"))
      (is (str/includes? err "input validation failed"))
      (is (str/includes? err "name"))
      (is (str/includes? err "page"))
      (is (str/includes? err "Retry with only the documented fields")))))

(deftest invoke-tool-execution-error-is-structured-json
  (testing "audit 2026-07 rec #7: an execution throw returns a JSON envelope
            with :tool :phase :class :message, and keeps the back-compat
            :error one-line string"
    (let [t (reify tool/Tool
              (-name [_] "boom")
              (-description [_] "always throws")
              (-input-schema [_] [:map])
              (-output-schema [_] :string)
              (-invoke [_ _ _] (throw (ex-info "explosion" {:why 42}))))
          result (tool/invoke-tool t {} {})
          parsed (json/parse-string result true)]
      (is (= "boom" (:tool parsed)))
      (is (= "execution" (:phase parsed)))
      (is (= "clojure.lang.ExceptionInfo" (:class parsed)))
      (is (= "explosion" (:message parsed)))
      (is (str/includes? (:error parsed) "Tool execution error"))
      (is (str/includes? (:error parsed) "explosion")))))


(deftest truncated-args-legible-error
  (testing "unterminated JSON arguments produce an actionable split hint,"
    " not a confusing schema error"
    (let [t (reify tool/Tool
              (-name [_] "echo")
              (-description [_] "echo")
              (-input-schema [_] [:map [:x :int]])
              (-output-schema [_] :string)
              (-invoke [_ args _] (:x args)))
          {:keys [result]}
          (first (tool/execute-tools {"echo" t} {}
                   [{:function {:name "echo"
                                :arguments "{\"x\": 1, \"payload\": \"<html>trunc"}}]))]
      (is (str/includes? result "TRUNCATED"))
      (is (str/includes? result "split")))))
