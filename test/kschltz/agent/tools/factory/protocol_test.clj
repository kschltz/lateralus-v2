(ns kschltz.agent.tools.factory.protocol-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.tools.factory.protocol :as proto]))

(def valid-spec
  {:name "add_two"
   :description "Add two integers"
   :input-schema "[:map [:a :int] [:b :int]]"
   :invoke "(fn [args _ctx] (str (+ (:a args) (:b args))))"})

(deftest tool-spec-is-closed
  (is (proto/valid-tool-spec? valid-spec))
  (is (not (proto/valid-tool-spec? (assoc valid-spec :extra 1))))
  (is (not (proto/valid-tool-spec? (assoc valid-spec :name "not-portable!"))))
  (is (proto/valid-tool-spec?
       (assoc valid-spec
              :interceptor-slot :observe
              :interceptor-enter "(fn [ctx] ctx)"))))
