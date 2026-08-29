(ns kschltz.agent.tools.factory.session-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.factory.protocol :as proto]
            [kschltz.agent.tools.factory.session :as session]))

(def spec
  {:name "add_two"
   :description "Add two integers"
   :input-schema "[:map [:a :int] [:b :int]]"
   :invoke "(fn [args _ctx] (str (+ (:a args) (:b args))))"})

(deftest define-then-invoke-from-store-registry
  (let [store (session/factory-session {})
        status (proto/-define! store spec {:reserved-names #{"file_read"}})]
    (is (true? (:ok status)))
    (is (= "add_two" (:tool-name status)))
    (is (= ["add_two"] (:ephemeral (proto/-status store))))
    (let [tool (get (proto/-registry store) "add_two")]
      (is (tool/tool? tool))
      (is (= "3" (tool/invoke-tool tool {:a 1 :b 2} {}))))))

(deftest define-rejects-reserved-name
  (let [store (session/factory-session {})]
    (is (thrown-with-msg? Exception #"collides"
                          (proto/-define! store spec {:reserved-names #{"add_two"}})))))

(deftest forget-and-rehydrate
  (let [store (session/factory-session {})]
    (proto/-define! store spec {})
    (is (:removed (proto/-forget! store "add_two")))
    (is (empty? (proto/-registry store)))
    (let [hydrated (proto/-rehydrate! store {"add_two" spec})]
      (is (true? (:ok hydrated)))
      (is (= ["add_two"] (:rehydrated hydrated)))
      (is (= "3" (tool/invoke-tool (get (proto/-registry store) "add_two")
                                   {:a 1 :b 2} {}))))))

(deftest interceptor-is-stored-by-slot
  (let [store (session/factory-session {})
        ix-spec (assoc spec
                       :name "flag_tool"
                       :interceptor-slot :observe
                       :interceptor-enter "(fn [ctx] (assoc ctx :flag true))")]
    (proto/-define! store ix-spec {})
    (let [ixs (proto/-interceptors store :observe)]
      (is (= 1 (count ixs)))
      (is (= {:flag true} ((:enter (first ixs)) {}))))))

(deftest rehydrate-surfaces-compile-errors
  ;; previously errors were swallowed, so tool_define looked like a fake
  ;; success: define ok → tool silently missing forever.
  (let [eng (session/factory-session nil)
        r (proto/-rehydrate! eng {"broken" {:name "broken"
                                            :description "x"
                                            :input-schema "[:map [:a :int]]"
                                            :invoke "(fn [args ctx] (map #(f %) args))"}})]
    (is (false? (:ok r)))
    (is (seq (:errors r)))
    (is (string? (get-in r [:errors 0 :error])) "error text surfaced")
    (is (str/includes? (str (get-in r [:errors 0 :error])) "failed to evaluate")))
  (let [eng (session/factory-session nil)]
    (is (:ok (proto/-rehydrate! eng {})) "empty specs rehydrate cleanly")))
