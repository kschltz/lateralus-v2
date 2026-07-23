(ns kschltz.agent.tools.runtime.protocol-test
  "Tests for the ClojureRuntime protocol and its shared data schemas."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [kschltz.agent.tools.runtime.protocol :as proto]))

(deftype StubRuntime []
  proto/ClojureRuntime
  (-eval [_ _code _opts] {:ns "x" :forms 0 :value nil :output "" :error nil})
  (-add-libs [_ _coords _opts] {:added [] :error nil})
  (-loaded-libs [_] [])
  (-capabilities [_] {:eval? true :add-libs? false :network? false}))

(deftest capabilities?-detects-protocol-implementers
  (testing "capabilities? is true for a ClojureRuntime and false otherwise"
    (is (proto/capabilities? (->StubRuntime)))
    (is (not (proto/capabilities? {})))
    (is (not (proto/capabilities? nil)))))

(deftest capabilities-never-raises
  (testing "-capabilities returns a plain map without raising"
    (let [caps (proto/-capabilities (->StubRuntime))]
      (is (map? caps))
      (is (contains? caps :eval?))
      (is (contains? caps :add-libs?))
      (is (contains? caps :network?)))))

(deftest protocol-methods-dispatch
  (testing "every protocol method dispatches to the implementation"
    (let [rt (->StubRuntime)]
      (is (= "x" (:ns (proto/-eval rt "(+ 1 1)" {}))))
      (is (= [] (:added (proto/-add-libs rt {} {}))))
      (is (= [] (proto/-loaded-libs rt))))))

(deftest shared-schemas-are-valid-malli
  (testing "Coords, EvalResult and AddLibsResult compile and validate
            representative data"
    (is (m/validate proto/Coords '{org.clojure/data.json {:mvn/version "2.5.0"}}))
    (is (m/validate proto/EvalResult {:ns "user" :forms 1 :value "3"
                                      :values ["3"] :output ""
                                      :status :ok :error nil}))
    (is (m/validate proto/EvalResult {:ns "user" :forms 1 :value nil
                                      :values [] :output "boom"
                                      :status :error :error "ex"
                                      :error-detail {:class "java.lang.Exception"
                                                     :message "ex"
                                                     :cause nil :data nil :trace []}}))
    (is (m/validate proto/EvalResult {:ns "user" :forms 1 :value "x"
                                      :values ["x"] :output "..."
                                      :status :truncated :truncated? true
                                      :error nil}))
    (is (m/validate proto/AddLibsResult {:added ["a/b"] :status :ok :error nil}))
    (is (m/validate proto/AddLibsResult {:added [] :status :error :error "boom"
                                         :error-detail {:class "c" :message "m"
                                                        :cause nil :data nil :trace []}}))
    (is (not (m/validate proto/AddLibsResult {:added "nope" :status :ok :error nil})))
    (is (not (m/validate proto/EvalResult {:ns "user" :forms 1 :value "3"
                                           :output "" :error nil}))
        "EvalResult without :status/:values is rejected now")))
