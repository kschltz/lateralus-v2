(ns kschltz.agent.plugins.skills-test
  "Tests for the skills plugin: catalog on :compose, tools on the
   registry, and the prompt-shrink invariant the plugin exists for."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.plugins.skills :as plugins.skills]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.skills :as skills]
            [kschltz.agent.tool :as tool]))

(def ^:private dir
  (str (System/getProperty "java.io.tmpdir") "/lat-skills-plugin/secrets-x"))

(defn- fixture-fn [_]
  (let [d (io/file dir)]
    (when (.exists d)
      (doseq [c (.listFiles d)] (when (.isFile c) (.delete c)))))
  (.mkdirs (io/file dir))
  (spit (io/file dir "runbook.edn")
        (pr-str {:name "deploy-runbook"
                 :description "Deploy steps. Use when deploying."
                 :body (str/join "\n" (repeat 60 "Runbook step with details."))})))

(use-fixtures :each (fn [f]
                      (let [d (io/file dir)]
                        (.mkdirs d)
                        (spit (io/file d "runbook.edn")
                              (pr-str {:name "deploy-runbook"
                                       :description "Deploy steps. Use when deploying."
                                       :body (str/join "
" (repeat 60 "Runbook step with details."))})))
                      (f)
                      (doseq [c (file-seq (io/file dir))]
                        (when (.isFile c) (.delete c)))))

(defn- samples->store [] (skills/load-skills-dir dir))

(deftest plugin-registers-tools-and-catalog
  (testing "guard registers the two tools; compose appends the fragment"
    (let [p (plugins.skills/skills-plugin {:store (skills/load-skills-dir dir)})
          cat-ix (some #(when (= :kschltz.agent.plugins.skills/catalog (:name %)) %) p)
          reg-ix (some #(when (= :kschltz.agent.plugins.skills/register-tools (:name %)) %) p)]
      (is (= :compose (:slot cat-ix)))
      (is (= :guard (:slot reg-ix)))
      (let [ctx ((:enter reg-ix) {:agent/tool-registry {}})
            reg (:agent/tool-registry ctx)
            ctx2 ((:enter cat-ix) (assoc ctx :agent/state {:agent/system-message "lateralus-v2 MVP"}))
            sys (get-in ((:enter ix/compose-context) ctx2)
                        [:llm/request :messages 0 :content])]
        (is (contains? reg "load_skill"))
        (is (contains? reg "read_skill_file"))
        ;; Tier 1 in prompt...
        (is (str/includes? sys "deploy-runbook"))
        (is (str/includes? sys "Deploy steps"))
        ;; ...Tier 2 never
        (is (not (str/includes? sys "Runbook step with details.")))
        ;; ...Tier 2 via tool result only
        (is (str/includes? (tool/invoke-tool (get reg "load_skill") {:name "deploy-runbook"} nil)
                           "Runbook step with details."))))))
