(ns kschltz.agent.tools.factory.plugin-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.tools.factory.plugin :as factory.plugin]
            [kschltz.agent.tools.factory.protocol :as proto]
            [kschltz.agent.tools.factory.session :as session]))

(deftest factory-plugin-seeds-session-and-runs-interceptor
  (let [spec {:name "flag_tool"
              :description "x"
              :input-schema "[:map]"
              :invoke "(fn [_ _] \"ok\")"
              :interceptor-slot :observe
              :interceptor-enter "(fn [ctx] (assoc ctx :flag true))"}
        store (session/factory-session {})
        _ (proto/-define! store spec {})
        p (factory.plugin/factory-plugin store)
        chain (plugin/assemble-chain [(plugins.base/base-plugin) p])
        seed (some #(when (= :kschltz.agent.tools.factory.plugin/seed-factory
                             (:name %))
                      %)
                   chain)
        observe (some #(when (= :kschltz.agent.tools.factory.plugin/observe
                                (:name %))
                         %)
                      chain)
        seeded ((:enter seed)
                {:agent/state {:agent/runtime-tools {"flag_tool" spec}}})
        observed ((:enter observe) seeded)]
    (is (= store (:agent/factory-session seeded)))
    (is (true? (:flag observed)))
    (is (= :factory (-> p meta :plugin/name)))
    (let [guide (some #(when (= :kschltz.agent.tools.factory.plugin/factory-guidance
                                (:name %))
                         %)
                      chain)]
      (is (some? guide))
      (is (re-find #"tool_define" factory.plugin/system-guidance))
      (is (re-find #"tool_test" factory.plugin/system-guidance))
      (is (re-find #"tool_define"
                   (:agent/system-append ((:enter guide) {})))))))
