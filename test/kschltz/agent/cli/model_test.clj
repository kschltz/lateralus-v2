(ns kschltz.agent.cli.model-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.cli.model :as model]))

(deftest parse-selection-smoke
  (is (= "b" (model/parse-selection "2" ["a" "b" "c"])))
  (is (= :blank (model/parse-selection "" ["a"]))))

(deftest filter-models-substring
  (let [ids ["deepseek-v4-flash" "deepseek-v4-pro" "gemma4:31b" "glm-5.1"]]
    (is (= ids (model/filter-models ids nil)))
    (is (= ids (model/filter-models ids "")))
    (is (= ["deepseek-v4-flash" "deepseek-v4-pro"]
           (model/filter-models ids "deep")))
    (is (= ["gemma4:31b"] (model/filter-models ids "GEMMA")))))

(deftest parse-catalog-command
  (is (= :blank (model/parse-catalog-command "")))
  (is (= :list (model/parse-catalog-command "?")))
  (is (= :list (model/parse-catalog-command "/")))
  (is (= {:filter "deepseek"} (model/parse-catalog-command "/deepseek")))
  (is (= {:filter "qwen"} (model/parse-catalog-command "?qwen")))
  (is (= {:raw "2"} (model/parse-catalog-command "2")))
  (is (= {:raw "glm-5.1"} (model/parse-catalog-command "glm-5.1"))))

(defn- scripted [lines]
  (let [q (atom lines)]
    (fn []
      (let [l (first @q)]
        (swap! q rest)
        l))))

(deftest catalog-pick-filter-then-number
  (let [out (java.io.StringWriter.)
        ids ["deepseek-v4-flash" "deepseek-v4-pro" "gemma4:31b"]
        chosen (model/catalog-pick!
                {:out out
                 :ids ids
                 :default "gemma4:31b"
                 :read-line-fn (scripted ["/deep" "1"])})]
    (is (= "deepseek-v4-flash" chosen))
    (is (str/includes? (str out) "matching /deep"))))

(deftest catalog-pick-initial-term
  (let [out (java.io.StringWriter.)
        ids ["a-cloud" "b-local" "deepseek-v4-flash"]
        chosen (model/catalog-pick!
                {:out out
                 :ids ids
                 :initial-term "deep"
                 :read-line-fn (scripted [""])})]
    (is (= "deepseek-v4-flash" chosen))
    (is (str/includes? (str out) "matching /deep"))))

(deftest catalog-pick-question-mark-lists-all
  (let [out (java.io.StringWriter.)
        ids ["a" "b" "c"]
        chosen (model/catalog-pick!
                {:out out
                 :ids ids
                 :read-line-fn (scripted ["?" "2"])})]
    (is (= "b" chosen))))
