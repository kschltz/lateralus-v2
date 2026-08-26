(ns kschltz.agent.tools.workflow.engine-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.workflow.engine :as engine]))

(defn- action
  [name needs produces values]
  {:name name
   :needs needs
   :produces produces
   :run {:op :literal :values values}})

(def diamond
  "A produces x; B and C both need x; D needs B+C outputs."
  {"A" (action "A" [] ["x"] {"x" 1})
   "B" (action "B" ["x"] ["y"] {"y" 2})
   "C" (action "C" ["x"] ["z"] {"z" 3})
   "D" (action "D" ["y" "z"] ["w"] {"w" 4})})

(deftest diamond-runs-b-and-c-in-one-wave
  (let [result (engine/schedule diamond {} {})]
    (is (= :done (:status result)))
    (is (false? (:blocked? result)))
    (is (= 3 (count (:parallel-waves result))))
    (is (= ["A"] (first (:parallel-waves result))))
    (is (= #{"B" "C"} (set (second (:parallel-waves result)))))
    (is (= ["D"] (last (:parallel-waves result))))
    (is (= {"x" 1 "y" 2 "z" 3 "w" 4} (:store result)))
    (is (empty? (:errors result)))))

(deftest cycle-is-blocked-without-running
  (let [actions {"f" (action "f" ["b"] ["a"] {"a" 1})
                 "g" (action "g" ["a"] ["b"] {"b" 1})}
        result (engine/schedule actions {} {})]
    (is (= :blocked (:status result)))
    (is (true? (:blocked? result)))
    (is (empty? (:ran result)))
    (is (empty? (:parallel-waves result)))
    (is (seq (:cycle result)))
    (is (= #{"f" "g"} (set (apply concat (:cycle result)))))
    (is (some #(= "cycle" (:error %)) (:errors result)))))

(deftest missing-input-blocks
  (let [actions {"h" (action "h" ["seed"] ["out"] {"out" 1})}
        result (engine/schedule actions {} {})]
    (is (= :blocked (:status result)))
    (is (true? (:blocked? result)))
    (is (empty? (:ran result)))
    (is (= [{:action "h" :missing ["seed"]}] (:missing result)))
    (is (some #(= "missing" (:error %)) (:errors result)))))

(deftest eval-run-compiles-fn-of-store
  (let [actions {"n" {:name "n"
                      :needs []
                      :produces ["n"]
                      :run {:op :eval :code "(fn [store] {\"n\" (inc (get store \"seed\" 41))})"}}}
        result (engine/schedule actions {"seed" 41} {})]
    (is (= :done (:status result)))
    (is (= 42 (get-in result [:store "n"])))))

(deftest tool-run-uses-registry
  (let [stub (reify tool/Tool
               (-name [_] "stub_echo")
               (-description [_] "echo")
               (-input-schema [_] :map)
               (-output-schema [_] :string)
               (-invoke [_ args _ctx]
                 (pr-str {"echo" (:msg args)})))
        actions {"t" {:name "t"
                      :needs []
                      :produces ["echo"]
                      :run {:op :tool :name "stub_echo" :args {:msg "hi"}}}}
        result (engine/schedule actions {} {:agent/tool-registry {"stub_echo" stub}})]
    (is (= :done (:status result)))
    (is (= "hi" (get-in result [:store "echo"])))))
