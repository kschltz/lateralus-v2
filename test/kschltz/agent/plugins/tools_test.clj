(ns kschltz.agent.plugins.tools-test
  "Tests for the tools plugin that seeds :agent/tool-registry."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.plugins.tools :as plugins.tools]
            [kschltz.agent.tool :as tool]))

(deftype FakeTool []
  tool/Tool
  (-name [_] "fake")
  (-description [_] "fake tool")
  (-input-schema [_] [:map])
  (-output-schema [_] :string)
  (-invoke [_ _ _ctx] "ok"))

(deftest tools-plugin-seeds-registry-on-context
  (testing "the tools plugin places the registry on the context at the guard slot"
    (let [registry {"fake" (->FakeTool)}
          plugin (plugins.tools/tools-plugin registry)
          chain (plugin/assemble-chain [(plugins.base/base-plugin) plugin])
          seed-ix (some #(when (= :kschltz.agent.plugins.tools/seed-registry (:name %)) %) chain)]
      (is (some? seed-ix))
      (is (= :guard (:plugin/slot seed-ix)))
      (is (= :tools (-> plugin meta :plugin/name))))))

(deftest tools-plugin-works-with-empty-registry
  (testing "an empty registry plugin is valid and makes tool loop a no-op"
    (let [plugin (plugins.tools/tools-plugin)]
      (is (vector? plugin))
      (is (= 1 (count plugin))))))

(deftest session-tool-overlay-filters-seed-and-refresh
  (let [registry {"fake" (->FakeTool)
                  "other" (->FakeTool)}
        state {:agent/disabled-tools ["fake"]}
        plugin (plugins.tools/tools-plugin registry)
        chain (plugin/assemble-chain [(plugins.base/base-plugin) plugin])
        seed (some #(when (= :kschltz.agent.plugins.tools/seed-registry
                            (:name %))
                      %)
                   chain)
        seeded ((:enter seed) {:agent/state state})
        refreshed (plugins.tools/refresh-mcp-tools
                   (assoc seeded :llm/request {:messages []}))]
    (is (= #{"other"} (set (keys (:agent/tool-registry seeded)))))
    (is (= #{"fake" "other"}
           (set (keys (:agent/static-tool-registry seeded)))))
    (is (= #{"other"} (set (keys (:agent/tool-registry refreshed)))))
    (is (= 1 (count (get-in refreshed [:llm/request :tools]))))))
