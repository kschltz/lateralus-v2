(ns kschltz.agent.plugins.base-test
  "Tests for the default base plugin.

   Verifies that the base plugin contributes the core interceptors
   in the right slots and that assembling it produces the expected
   default chain order. The base plugin is now tool-aware: it includes
   the loop interceptors from `kschltz.agent.loop`, which are no-ops
   when no tool registry is seeded on the context. The :guard slot also
   carries the per-stage `logging` interceptor (first), and :finalize
   also carries `ensure-text-response` (after tool-loop)."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.logging :as logging]
            [kschltz.agent.loop :as loop]
            [kschltz.agent.loop.retry :as retry]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as base]
            [kschltz.agent.runtime-reload :as runtime-reload]
            [kschltz.agent.transitions.interceptors :as tr.ix]))

(defn- by-slot [plugin slot]
  (filterv #(= slot (:slot %)) plugin))

(deftest base-plugin-has-correct-slots
  (testing "base plugin contributes core interceptors in expected slots"
    (let [p (base/base-plugin)]
      (is (= :base (-> p meta :plugin/name)))
      (is (= [::logging/logging ::ix/error-boundary]
             (mapv :name (by-slot p :guard))))
      (is (= [::runtime-reload/reload-notice]
             (mapv :name (by-slot p :enrich))))
      (is (= [::ix/compose-context ::loop/inject-tools]
             (mapv :name (by-slot p :compose))))
      (is (= [::loop/llm-call-with-self-heal ::ix/llm-call ::ix/parse-response]
             (mapv :name (by-slot p :llm))))
      (is (= [::loop/dispatch-tools
              ::tr.ix/harvest-transitions
              ::tr.ix/apply-transitions
              ::retry/retry-now-available
              ::retry/nudge-untested-runtime-tools
              ::loop/compose-tool-results]
             (mapv :name (by-slot p :tools))))
      (is (= [::loop/tool-loop ::loop/ensure-text-response]
             (mapv :name (by-slot p :finalize))))
      (is (= [::ix/store-exchange]
             (mapv :name (by-slot p :history))))
      (is (= [::ix/deliver-responses]
             (mapv :name (by-slot p :observe))))
      (is (= [::ix/notify]
             (mapv :name (by-slot p :notify)))))))

(deftest assembled-base-matches-default-exchange-order
  (testing "assembling only the base plugin yields the canonical stage order"
    (let [chain (plugin/assemble-chain [(base/base-plugin)])]
      (is (= [::logging/logging
              ::ix/error-boundary
              ::runtime-reload/reload-notice
              ::ix/compose-context
              ::loop/inject-tools
              ::loop/llm-call-with-self-heal
              ::ix/llm-call
              ::ix/parse-response
              ::loop/dispatch-tools
              ::tr.ix/harvest-transitions
              ::tr.ix/apply-transitions
              ::retry/retry-now-available
              ::retry/nudge-untested-runtime-tools
              ::loop/compose-tool-results
              ::loop/tool-loop
              ::loop/ensure-text-response
              ::ix/summarize-history
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
