(ns kschltz.agent.tools.factory.compile-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.factory.compile :as compile]
            [kschltz.agent.tools.factory.protocol :as proto]))

(deftest compile-spec-builds-invokable-tool
  (let [compiler (compile/jvm-compiler)
        spec {:name "add_two"
              :description "Add two integers"
              :input-schema "[:map [:a :int] [:b :int]]"
              :invoke "(fn [args _ctx] (str (+ (:a args) (:b args))))"}
        result (proto/-compile-spec compiler spec)]
    (is (true? (:ok result)))
    (is (tool/tool? (:tool result)))
    (is (= "3" (tool/invoke-tool (:tool result) {:a 1 :b 2} {})))))

(deftest compiled-tool-accepts-model-authored-one-arity-function
  (let [compiler (compile/jvm-compiler)
        result (proto/-compile-spec
                compiler
                {:name "credential_status"
                 :description "Classify credential presence"
                 :input-schema "[:map [:credential :string]]"
                 :invoke "(fn [{:keys [credential]}] (if (seq credential) \"available\" \"missing\"))"})]
    (is (true? (:ok result)))
    (is (= "available"
           (tool/invoke-tool (:tool result) {:credential "secret"} {})))))

(deftest compile-spec-rejects-bad-schema
  (let [compiler (compile/jvm-compiler)
        result (proto/-compile-spec compiler
                                    {:name "bad"
                                     :description "x"
                                     :input-schema "not-a-schema-!!!"
                                     :invoke "(fn [args _ctx] \"ok\")"})]
    (is (false? (:ok result)))
    (is (= "compile" (:phase result)))))

(deftest compile-fn-requires-ifn
  (is (thrown-with-msg? Exception #"function"
                        (compile/compile-fn "42"))))
(deftest compile-fn-reads-fn-literals-and-regexes
  ;; regression: clojure.edn/read raised "No dispatch macro for: (" on
  ;; model bodies using #(…) and #"…" — the tool then silently vanished
  ;; at every rehydrate (sessions 675706dd / 92150f99).
  (let [f (compile/compile-fn
           "(fn [args _ctx] (mapv #(clojure.string/upper-case (str %)) (re-seq #\"[a-z]+\" (str (:text args)))))")]
    (is (= ["AB" "CD"] (f {:text "ab cd"} nil)))))

(deftest read-form-still-blocks-reader-eval
  (is (thrown? Exception (compile/compile-fn "#=(+ 1 2)"))))
