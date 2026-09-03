(ns kschltz.agent.store.duckdb-test
  "JDBC leaf tests. Require duckdb_jdbc on the classpath (main dep)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.store.duckdb :as duckdb]
            [kschltz.agent.store.file-index :as index]
            [kschltz.agent.store.protocol :as proto]))

(deftest in-memory-duckdb-roundtrip
  (let [e (duckdb/duckdb-store {:path ":memory:"})]
    (try
      (proto/-upsert! e :file_index [:path]
                      {:path "/a.txt" :sha256 "abc" :size 2 :mtime 1
                       :content "hi" :indexed-at 1})
      (is (= [{:path "/a.txt" :sha256 "abc" :size 2 :mtime 1
               :content "hi" :indexed-at 1}]
             (proto/-select e :file_index {:where {:path "/a.txt"}})))
      (proto/-insert! e :file_edits
                      {:id "e1" :path "/a.txt" :tool "file_write"
                       :sha256-before nil :sha256-after "abc"
                       :start-line nil :end-line nil :ts 9})
      (proto/-insert! e :file_edits
                      {:id "e0" :path "/a.txt" :tool "file_update"
                       :sha256-before "abc" :sha256-after "def"
                       :start-line nil :end-line nil :ts 3})
      (is (= ["e0" "e1"]
             (mapv :id (proto/-select e :file_edits {:where {:path "/a.txt"}
                                                    :order [:ts]}))))
      (is (= ["e1" "e0"]
             (mapv :id (proto/-select e :file_edits {:where {:path "/a.txt"}
                                                    :order [:ts]
                                                    :desc true}))))
      (is (= {:rows 1} (proto/-delete! e :file_index {:path "/a.txt"})))
      (is (empty? (proto/-select e :file_index {:where {:path "/a.txt"}})))
      (finally
        (proto/-close e)))))

(deftest duckdb-file-index-search
  (let [e (duckdb/duckdb-store)
        i (index/file-index e)]
    (try
      (index/-upsert-file! i {:path "/ws/n.txt" :content "needle here\nrest"})
      (let [hits (index/-search i {:path-prefix "/ws" :pattern "needle" :max-results 5})]
        (is (= 1 (count hits)))
        (is (= 1 (:line (first hits)))))
      (finally
        (index/-close i)
        (proto/-close e)))))

(deftest duckdb-file-store-persists
  (let [dir (doto (io/file (System/getProperty "java.io.tmpdir")
                           (str "lat-duck-" (random-uuid)))
              (.mkdirs))
        path (str (io/file dir "store.duckdb"))
        e1 (duckdb/duckdb-store {:path path})]
    (try
      (proto/-upsert! e1 :file_index [:path]
                      {:path "/p.txt" :sha256 "s" :size 1 :mtime 1
                       :content "x" :indexed-at 1})
      (finally
        (proto/-close e1)))
    (let [e2 (duckdb/duckdb-store {:path path})]
      (try
        (is (= ["/p.txt"]
               (mapv :path (proto/-select e2 :file_index {}))))
        (finally
          (proto/-close e2))))
    (doseq [^java.io.File f (reverse (file-seq dir))]
      (.delete f))))
