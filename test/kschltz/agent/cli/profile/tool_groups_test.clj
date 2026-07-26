(ns kschltz.agent.cli.profile.tool-groups-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.cli.profile.templates :as t]
            [kschltz.agent.cli.profile.tool-groups :as tg]))

(def ids [:files :self :config :clojure :web :runtime])

(deftest space-toggles-cursor-row
  (let [g (t/default-tool-groups false)
        r (tg/apply-command {:groups g :ids ids :cursor 0} " ")]
    (is (false? (get-in r [:groups :files])))
    (is (false? (:done? r)))))

(deftest enter-accepts
  (is (true? (:done? (tg/apply-command {:groups {} :ids ids :cursor 0} "")))))

(deftest number-toggles-and-moves-cursor
  (let [g (t/default-tool-groups false)
        r (tg/apply-command {:groups g :ids ids :cursor 0} "4")]
    (is (false? (get-in r [:groups :clojure])))
    (is (= 3 (:cursor r)))))

(deftest all-and-none
  (let [g (t/default-tool-groups false)
        off (:groups (tg/apply-command {:groups g :ids ids :cursor 0} "z"))
        on (:groups (tg/apply-command {:groups off :ids ids :cursor 0} "a"))]
    (is (every? false? (map off ids)))
    (is (every? true? (map on ids)))))

(deftest prompt-disables-runtime
  (let [out (java.io.StringWriter.)
        pw (java.io.PrintWriter. out true)
        lines (atom ["6" ""])
        read #(let [l (first @lines)] (swap! lines rest) l)
        groups (tg/prompt! pw read (t/default-tool-groups false) false)]
    (is (false? (:runtime groups)))
    (is (true? (:files groups)))
    (is (true? (:config groups)))
    (is (str/includes? (str out) "Tool groups"))))

(deftest build-omits-disabled-groups
  (testing "runtime off removes runtime from registry"
    (let [edn (t/build {:backend :ollama-local
                        :tool-groups {:runtime false}})
          keys (map :key (:lateralus/tool-registry edn))]
      (is (false? (get-in edn [:lateralus/runtime-tools :enabled?])))
      (is (not-any? #{:lateralus/runtime-tools} keys))))
  (testing "web off forces provider none and drops web tools"
    (let [edn (t/build {:backend :ollama-local
                        :web-provider :ddg
                        :tool-groups {:web false}})
          keys (map :key (:lateralus/tool-registry edn))]
      (is (= :none (get-in edn [:lateralus/web-tools :provider])))
      (is (not-any? #{:lateralus/web-tools} keys)))))
