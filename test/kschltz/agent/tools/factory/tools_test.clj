(ns kschltz.agent.tools.factory.tools-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.tool :as tool]
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
    (is (= #{ "tool_define" "tool_forget" "tool_list_runtime" "tool_promote"}
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
