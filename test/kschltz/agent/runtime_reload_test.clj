(ns kschltz.agent.runtime-reload-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.runtime-reload :as reload]))

(defn- fake-runtime
  [rebuild]
  {:state (atom {:agent/runtime-reload {:namespaces ["pending"]}})
   :chain (atom [{:name :old :slot :compose :enter identity}])
   :agent-map (cond-> {}
                rebuild (assoc :agent/rebuild-chain rebuild))})

(deftest reload-applies-a-fresh-chain-and-consumes-request
  (let [runtime (fake-runtime (fn [] [{:name :new :slot :compose :enter identity}]))]
    (reload/apply! runtime
                   {:namespaces ["kschltz.agent.tools.file-path"
                                 "kschltz.agent.tools.file-path"]})
    (is (= :new (:name (first @(:chain runtime)))))
    (is (nil? (:agent/runtime-reload @(:state runtime))))
    (let [st (:agent/runtime-reload-status @(:state runtime))]
      (is (true? (:ok st)))
      (is (= :reloaded (:status st)))
      (is (= ["kschltz.agent.tools.file-path"] (:namespaces st)))
      (is (= 1 (:revision st)))
      (is (= 1 (:interceptor-count st))))))

(deftest reload-reports-safe-boundaries
  (testing "core protocol/class namespaces require a process restart"
    (let [runtime (fake-runtime (fn [] [{:name :new}]))]
      (reload/apply! runtime {:namespaces ["kschltz.agent.runtime"]})
      (is (= :restart-required
             (get-in @(:state runtime)
                     [:agent/runtime-reload-status :status])))
      (is (= :old (:name (first @(:chain runtime)))))))
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
      (is (true? (get-in @(:state runtime)
                         [:agent/runtime-reload-status :rolled-back?])))
      (is (nil? (:agent/runtime-reload @(:state runtime)))))))

(deftest reload-rolls-back-when-probe-cannot-json-encode-tools
  (let [poison {:name :poison
                :slot :compose
                :enter (fn [ctx]
                         (assoc-in ctx [:llm/request :tools]
                                   [{:type "function"
                                     :function {:name "web_search"
                                                :parameters {:pattern (re-pattern "\\S")
                                                             :oops (Object.)}}}]))}
        runtime (fake-runtime (fn [] [poison]))]
    (reload/apply! runtime {:namespaces ["kschltz.agent.runtime-reload-test"]})
    (is (= :rolled-back
           (get-in @(:state runtime)
                   [:agent/runtime-reload-status :status])))
    (is (= :old (:name (first @(:chain runtime)))))
    (is (string? (get-in @(:state runtime)
                         [:agent/runtime-reload-status :error])))))

(deftest notice-interceptor-appends-rollback-copy
  (let [enter (:enter (reload/notice-interceptor))
        ctx (enter {:agent/state {:agent/runtime-reload-status
                                  {:status :rolled-back
                                   :error "Cannot JSON encode object"}}})]
    (is (re-find #"rolled back" (:agent/system-append ctx)))
    (is (re-find #"Tell the human" (:agent/system-append ctx)))))
