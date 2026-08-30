(ns kschltz.agent.tools.factory.promote-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.factory.promote :as promote]
            [kschltz.agent.tools.factory.protocol :as proto]
            [kschltz.agent.tools.factory.session :as session]))

(def spec
  {:name "add_two"
   :description "Add two integers"
   :input-schema "[:map [:a :int] [:b :int]]"
   :invoke "(fn [args _ctx] (str (+ (:a args) (:b args))))"})

(deftest promote-writes-workspace-plugin-and-stays-callable
  (let [root (.getPath (io/file (System/getProperty "java.io.tmpdir")
                                (str "lateralus-promote-" (random-uuid))))
        store (session/factory-session {:workspace-root root})]
    (try
      (proto/-define! store spec {})
      (proto/-record-test! store "add_two" (proto/spec-id spec))
      (let [status (proto/-promote! store "add_two"
                                    {:as-plugin true
                                     :target :workspace
                                     :workspace-root root})
            tool-clj (io/file root ".lateralus/promoted/add_two/tool.clj")
            plugin-clj (io/file root ".lateralus/promoted/add_two/plugin.clj")
            catalog (io/file root ".lateralus/promoted/catalog.edn")
            tool (get (proto/-registry store) "add_two")]
        (is (true? (:ok status)))
        (is (.isFile tool-clj))
        (is (.isFile plugin-clj))
        (is (.isFile catalog))
        (is (= :workspace (:target status)))
        (is (tool/tool? tool))
        (is (= "3" (tool/invoke-tool tool {:a 1 :b 2} {})))
        (is (= ["add_two"] (:promoted (proto/-status store))))
        (is (empty? (:ephemeral (proto/-status store))))
        (let [reg-fn (ns-resolve (the-ns 'lateralus.promoted.add-two) 'registry)
              loaded (reg-fn)]
          (is (= "3" (tool/invoke-tool (get loaded "add_two") {:a 1 :b 2} {})))))
      (finally
        (doseq [f (reverse (file-seq (io/file root)))]
          (.delete f))))))

(deftest name-helpers
  (is (= "add-two" (promote/kebab-name "add_two")))
  (is (= "AddTwoTool" (promote/record-name "add_two")))
  (is (= 'kschltz.agent.tools.promoted.add-two
         (promote/project-ns "add_two"))))
