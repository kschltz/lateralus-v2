(ns kschltz.agent.tools.factory.apply-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.plugins.tools :as plugins.tools]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.factory.apply :as apply]
            [kschltz.agent.tools.factory.session :as session]
            [kschltz.agent.transitions :as tr]
            [kschltz.agent.transitions.interceptors :as tr.ix]))

(def spec
  {:name "add_two"
   :description "Add two integers"
   :input-schema "[:map [:a :int] [:b :int]]"
   :invoke "(fn [args _ctx] (str (+ (:a args) (:b args))))"})

(deftest apply-queued-defines-tool-and-patches-request
  (let [store (session/factory-session {})
        envelope (tr/encode-result
                  {:ok true
                   :tool "tool_define"
                   :transition {:op :register-runtime-tool :spec spec}})
        ctx {:agent/state {}
             :agent/static-tool-registry {}
             :agent/factory-session store
             :agent/transitions [{:op :register-runtime-tool :spec spec}]
             :tool/results [{:call {:id "1" :function {:name "tool_define"}}
                             :result envelope}]
             :llm/request {:messages [] :tools []}}
        out (tr.ix/apply-queued-transitions ctx)
        parsed (json/parse-string (:result (first (:tool/results out))) true)
        tool (get (:agent/tool-registry out) "add_two")]
    (is (true? (:ok parsed)))
    (is (tool/tool? tool))
    (is (= "3" (tool/invoke-tool tool {:a 1 :b 2} out)))
    (is (some #(= "add_two" (get-in % [:function :name]))
              (get-in out [:llm/request :tools])))
    (is (= spec (get-in out [:agent/state-delta :agent/runtime-tools "add_two"])))))

(deftest factory-op-predicate
  (is (apply/factory-op? {:op :register-runtime-tool}))
  (is (not (apply/factory-op? {:op :set-llm}))))

(deftest refresh-live-tools-merges-factory-registry
  (let [store (session/factory-session {})
        _ (kschltz.agent.tools.factory.protocol/-define! store spec {})
        ctx (plugins.tools/refresh-live-tools
             {:agent/static-tool-registry {}
              :agent/factory-session store
              :llm/request {:tools []}})]
    (is (contains? (:agent/tool-registry ctx) "add_two"))))
