(ns kschltz.agent.runtime-reload-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.runtime-reload :as reload]))

(defn- fake-runtime
  [rebuild]
  {:state (atom {:agent/runtime-reload {:namespaces ["pending"]}})
   :chain (atom [{:name :old}])
   :agent-map (cond-> {}
                rebuild (assoc :agent/rebuild-chain rebuild))})

(deftest reload-applies-a-fresh-chain-and-consumes-request
  (let [runtime (fake-runtime (fn [] [{:name :new}]))]
    (reload/apply! runtime
                   {:namespaces ["kschltz.agent.tools.file-path"
                                 "kschltz.agent.tools.file-path"]})
    (is (= [{:name :new}] @(:chain runtime)))
    (is (nil? (:agent/runtime-reload @(:state runtime))))
    (is (= {:ok true
            :status :reloaded
            :namespaces ["kschltz.agent.tools.file-path"]
            :revision 1
            :interceptor-count 1}
           (:agent/runtime-reload-status @(:state runtime))))))

(deftest reload-reports-safe-boundaries
  (testing "core protocol/class namespaces require a process restart"
    (let [runtime (fake-runtime (fn [] [{:name :new}]))]
      (reload/apply! runtime {:namespaces ["kschltz.agent.runtime"]})
      (is (= :restart-required
             (get-in @(:state runtime)
                     [:agent/runtime-reload-status :status])))
      (is (= [{:name :old}] @(:chain runtime)))))
  (testing "ad-hoc runtimes without an Integrant rebuild closure are explicit"
    (let [runtime (fake-runtime nil)]
      (reload/apply! runtime
                     {:namespaces ["kschltz.agent.tools.file-path"]})
      (is (= :unavailable
             (get-in @(:state runtime)
                     [:agent/runtime-reload-status :status])))))
  (testing "rebuild failures are captured and consume the request"
    (let [runtime (fake-runtime
                   (fn []
                     (throw (ex-info "rebuild failed" {:phase :test}))))]
      (reload/apply! runtime
                     {:namespaces ["kschltz.agent.tools.file-path"]})
      (is (= :error
             (get-in @(:state runtime)
                     [:agent/runtime-reload-status :status])))
      (is (= "rebuild failed"
             (get-in @(:state runtime)
                     [:agent/runtime-reload-status :error])))
      (is (nil? (:agent/runtime-reload @(:state runtime)))))))
