(ns kschltz.agent.plugins.base-test
  "Tests for the default base plugin.

   Verifies that the base plugin contributes the core interceptors
   in the right slots and that assembling it produces the expected
   default chain order."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as base]))

(defn- by-slot [plugin slot]
  (filterv #(= slot (:slot %)) plugin))

(deftest base-plugin-has-correct-slots
  (testing "base plugin contributes core interceptors in expected slots"
    (let [p (base/base-plugin)]
      (is (= :base (-> p meta :plugin/name)))
      (is (= [::ix/error-boundary]
             (mapv :name (by-slot p :guard))))
      (is (= [::ix/compose-context]
             (mapv :name (by-slot p :compose))))
      (is (= [::ix/llm-call ::ix/parse-response]
             (mapv :name (by-slot p :llm))))
      (is (= [::ix/dispatch]
             (mapv :name (by-slot p :dispatch))))
      (is (= [::ix/store-exchange]
             (mapv :name (by-slot p :history))))
      (is (= [::ix/deliver-responses]
             (mapv :name (by-slot p :observe))))
      (is (= [::ix/notify]
             (mapv :name (by-slot p :notify)))))))

(deftest assembled-base-matches-default-exchange-order
  (testing "assembling only the base plugin yields the canonical stage order"
    (let [chain (plugin/assemble-chain [(base/base-plugin)])]
      (is (= [::ix/error-boundary
              ::ix/compose-context
              ::ix/llm-call
              ::ix/parse-response
              ::ix/dispatch
              ::ix/store-exchange
              ::ix/deliver-responses
              ::ix/notify]
             (mapv :name chain))))))

(deftest assembled-base-is-valid
  (testing "the assembled base plugin chain validates as interceptors"
    (let [chain (plugin/assemble-chain [(base/base-plugin)])]
      (is (every? map? chain))
      (is (every? :name chain))
      (is (every? (fn [ix]
                    (or (:enter ix) (:leave ix) (:error ix)))
                  chain)))))
