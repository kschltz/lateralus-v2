(ns kschltz.agent.plugins.workbench-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.plugins.workbench :as plugins.workbench]
            [kschltz.agent.workbench.guidance :as guidance]
            [kschltz.agent.workbench.protocol :as proto]))

(defn- fake-wb []
  (reify proto/Workbench
    (-url [_] "http://127.0.0.1:0")
    (-portal-url [_] nil)
    (-publish! [_ _])
    (-await-human! [_ _] {:text "x" :refs []})
    (-attach-selection! [_] nil)
    (-submit-portal! [_ _ _] {:id "1" :preview "p"})
    (-clear-portal! [_] {:ok true})
    (-snapshot [_] {})
    (-tools [_] {"portal_submit" :tool})
    (-close! [_] nil)))

(deftest workbench-plugin-seeds-tools-and-guidance
  (let [wb (fake-wb)
        p  (plugins.workbench/workbench-plugin wb)
        names (set (map :name p))]
    (is (= :workbench (:plugin/name (meta p))))
    (is (contains? names ::plugins.workbench/seed-workbench))
    (is (contains? names ::plugins.workbench/portal-guidance))
    (let [seed (first (filter #(= ::plugins.workbench/seed-workbench (:name %)) p))
          guide (first (filter #(= ::plugins.workbench/portal-guidance (:name %)) p))
          ctx1 ((:enter seed) {})
          ctx2 ((:enter guide) ctx1)]
      (is (= wb (:agent/workbench ctx2)))
      (is (= "portal_submit" (first (keys (:agent/tool-registry ctx2)))))
      (is (string? (:agent/system-append ctx2)))
      (is (re-find #"PORTAL IS THE RICH" (:agent/system-append ctx2)))
      (is (re-find #":cite" (:agent/system-append ctx2))))))

(deftest portal-guidance-lands-in-system-message
  (testing "enrich guidance is concatenated by compose-context"
    (let [wb (fake-wb)
          chain (plugin/assemble-chain
                 [(plugins.base/base-plugin)
                  (plugins.workbench/workbench-plugin wb)])
          ;; Run only enrich+compose style: manually apply guide then compose enter
          guide (first (filter #(= ::plugins.workbench/portal-guidance (:name %))
                               (plugins.workbench/workbench-plugin wb)))
          ctx   ((:enter guide)
                 {:agent/state {:agent/system-message "base-sys"
                                :model "stub"}
                  :exchange/user-text "hi"})
          out   ((:enter ix/compose-context) ctx)
          sys   (-> out :llm/request :messages first :content)]
      (is (re-find #"base-sys" sys))
      (is (re-find #"portal_submit" sys))
      (is (re-find #"PORTAL IS THE RICH" sys))
      (is (= guidance/portal-system-guidance
             (when (string? (:agent/system-append ctx))
               (:agent/system-append ctx)))))))
