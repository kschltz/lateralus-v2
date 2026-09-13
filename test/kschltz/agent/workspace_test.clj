(ns kschltz.agent.workspace-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.filesystem :as tools.filesystem]
            [kschltz.agent.workspace :as workspace]))

(def ^:private tmp-dir
  (delay (.getCanonicalPath (io/file (System/getProperty "java.io.tmpdir")
                                     (str "lat-ws-" (random-uuid))))))

(use-fixtures :each
  (fn [f]
    (.mkdirs (io/file @tmp-dir))
    (f)
    (doseq [f (.listFiles (io/file @tmp-dir))]
      (.delete f))
    (.delete (io/file @tmp-dir))))

(deftest validate-and-normalize-root
  (testing "existing directory normalizes to canonical path"
    (is (nil? (workspace/validate-root @tmp-dir)))
    (is (= (.getCanonicalPath (io/file @tmp-dir))
           (workspace/normalize-root @tmp-dir))))
  (testing "missing path is rejected"
    (is (string? (workspace/validate-root "/no/such/lateralus-workspace")))))

(deftest rebind-registry-swaps-file-tools
  (let [root @tmp-dir
        marker (io/file root "marker.txt")]
    (.createNewFile marker)
    (let [reg (workspace/rebind-registry
               {"file_read" (get (tools.filesystem/filesystem-registry
                                  {:workspace-root "/tmp/other"})
                                 "file_read")}
               {:file-tools {} :clojure-tools {}}
               root)
          tool (get reg "file_read")
          result (tool/invoke-tool tool {:path "marker.txt"} {})]
      (is (re-find #"marker" result)))))

(deftest effective-root-prefers-session-state
  (is (= (workspace/normalize-root "/tmp")
         (workspace/effective-root {:agent/workspace-root "/tmp"} "/other"))))
