(ns kschltz.agent.tools.file-index-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.store.file-index :as index]
            [kschltz.agent.store.memory :as memory]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.file-index :as tools]
            [kschltz.agent.tools.filesystem :as fs-tools])
  (:import [java.io File]))

(def ^:private dummy-ctx {})

(defn- tmp []
  (doto (File/createTempFile "lat-fit" "")
    (.delete)
    (.mkdirs)))

(defn- wipe [^File dir]
  (doseq [^File f (reverse (file-seq dir))]
    (.delete f)))

(deftest index-tools-empty-without-index
  (is (= {} (tools/index-tools nil "." {}))))

(deftest reindex-and-edits-tools
  (let [dir (tmp)
        i (index/file-index (memory/memory-store))
        reg (fs-tools/filesystem-registry
             {:workspace-root (str dir)
              :file-index i})]
    (try
      (is (contains? reg "file_reindex"))
      (is (contains? reg "file_edits"))
      (spit (io/file dir "a.txt") "indexed needle")
      (let [out (json/parse-string
                 (tool/invoke-tool (get reg "file_reindex") {:path "."} dummy-ctx)
                 true)]
        (is (true? (:ok out)))
        (is (= 1 (:files out))))
      (tool/invoke-tool (get reg "file_write")
                        {:path "b.txt" :content "from write" :create-dirs true}
                        dummy-ctx)
      (let [edits (json/parse-string
                   (tool/invoke-tool (get reg "file_edits") {:path "b.txt"} dummy-ctx)
                   true)]
        (is (= 1 (:count edits)))
        (is (= "file_write" (:tool (first (:edits edits))))))
      (let [hits (json/parse-string
                  (tool/invoke-tool (get reg "file_search")
                                    {:path "." :pattern "needle"}
                                    dummy-ctx)
                  true)]
        (is (some #(= "indexed needle" (:text %)) hits)))
      (finally
        (wipe dir)))))

(deftest filesystem-registry-stays-nine-without-index
  (is (= 9 (count (fs-tools/filesystem-registry)))))
