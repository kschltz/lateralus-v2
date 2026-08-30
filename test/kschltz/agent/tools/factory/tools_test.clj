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
