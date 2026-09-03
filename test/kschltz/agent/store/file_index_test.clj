(ns kschltz.agent.store.file-index-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.store.file-index :as index]
            [kschltz.agent.store.memory :as memory]
            [kschltz.agent.tools.file-safety :as fs])
  (:import [java.io File]
           [java.nio.file Files]))

(defn- idx []
  (index/file-index (memory/memory-store)))

(deftest upsert-lookup-and-search
  (let [i (idx)]
    (index/-upsert-file! i {:path "/ws/a.txt"
                            :content "alpha\nbeta line\n"
                            :sha256 "aa"})
    (is (= "aa" (:sha256 (index/-lookup i "/ws/a.txt"))))
    (is (true? (index/-indexed-under? i "/ws")))
    (is (false? (index/-indexed-under? i "/other")))
    (let [hits (index/-search i {:path-prefix "/ws" :pattern "beta" :max-results 10})]
      (is (= 1 (count hits)))
      (is (= 2 (:line (first hits))))
      (is (= "/ws/a.txt" (:file (first hits)))))))

(deftest record-edit-and-list
  (let [i (idx)]
    (index/-record-edit! i {:path "/ws/a.txt" :tool "file_write" :sha256-after "bb"})
    (let [edits (index/-edits i {:path "/ws/a.txt" :limit 10})]
      (is (= 1 (count edits)))
      (is (= "file_write" (:tool (first edits))))
      (is (= "bb" (:sha256-after (first edits)))))))

(deftest record-mutation-is-advisory
  (let [i (idx)
        dir (doto (File/createTempFile "lat-idx" "")
              (.delete)
              (.mkdirs))
        f (io/file dir "note.txt")]
    (spit f "hello index")
    (index/record-mutation! i {:path (.getPath f)
                               :tool "file_write"
                               :sha256-after (fs/sha256
                                              (Files/readAllBytes (.toPath f)))
                               :content "hello index"})
    (is (some? (index/-lookup i (.getPath f))))
    (is (= 1 (count (index/-edits i {:path (.getPath f)}))))
    (index/record-mutation! nil {:path "nope" :tool "file_write"})
    (doseq [^File x (reverse (file-seq dir))]
      (.delete x))))

(deftest reindex-tree-skips-blocked
  (let [i (idx)
        dir (doto (File/createTempFile "lat-reidx" "")
              (.delete)
              (.mkdirs))
        keep (io/file dir "keep.txt")
        blocked (io/file dir ".git" "config")]
    (spit keep "visible")
    (.mkdirs (.getParentFile blocked))
    (spit blocked "secret")
    (let [stats (index/reindex-tree! i (.toPath dir) fs/default-blocked-paths 65536)]
      (is (= 1 (:files stats)))
      (is (some? (index/-lookup i (.getPath keep))))
      (is (nil? (index/-lookup i (.getPath blocked)))))
    (doseq [^File x (reverse (file-seq dir))]
      (.delete x))))
