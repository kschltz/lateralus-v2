(ns kschltz.agent.tools.file-write-test
  "Direct tests for the `file-write` factory functions.

   The bulk of the `file_write` and `file_update` behavior is
   exercised end-to-end through the registry in
   `kschltz.agent.tools.filesystem-test`; this namespace exists
   primarily so the project quality gate (every src ns has a
   matching test ns) is satisfied. It also pins the factory arities
   and default-option behavior so callers that depend on the public
   arity of [[write-file]] and [[update-file]] get a clear signal if
   that shape ever drifts."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.store.file-index :as index]
            [kschltz.agent.store.memory :as memory]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.file-write :as fw])
  (:import [java.io File]))

(def ^:private tmp-dir
  (delay
    (let [dir (File/createTempFile "lateralus-fw-test" "")]
      (.delete dir)
      (.mkdirs dir)
      (.deleteOnExit dir)
      dir)))

(use-fixtures :each
  (fn [f]
    (doseq [^File fseq (reverse (file-seq @tmp-dir))]
      (when (not= fseq @tmp-dir)
        (.delete fseq)))
    (f)))

(def ^:private dummy-ctx {})

(deftest write-file-factory-defaults
  (testing "write-file with no args returns a working file_write Tool"
    (let [t (fw/write-file)]
      (is (tool/tool? t))
      (is (= "file_write" (tool/-name t)))))
  (testing "write-file with a workspace-root string still returns a working tool"
    (let [t (fw/write-file (str @tmp-dir))]
      (is (tool/tool? t))
      (is (= "file_write" (tool/-name t))))))

(deftest update-file-factory-defaults
  (testing "update-file with no args returns a working file_update Tool"
    (let [t (fw/update-file)]
      (is (tool/tool? t))
      (is (= "file_update" (tool/-name t)))))
  (testing "update-file with a workspace-root string still returns a working tool"
    (let [t (fw/update-file (str @tmp-dir))]
      (is (tool/tool? t))
      (is (= "file_update" (tool/-name t))))))

(deftest write-file-roundtrips-through-tool-invocation
  (testing "a `fw/write-file` instance writes a new file and reports success"
    (let [t (fw/write-file (str @tmp-dir))
          out (tool/invoke-tool t
                                {:path "hello.txt"
                                 :content "hi\n"
                                 :create-dirs true}
                                dummy-ctx)
          parsed (json/parse-string out true)]
      (is (true? (:created parsed)) (str "out=" out))
      (is (true? (:changed parsed)))
      (is (string? (:sha256 parsed)))
      (is (= "hi\n" (slurp (io/file @tmp-dir "hello.txt")))))))

(deftest write-file-records-advisory-index
  (let [i (index/file-index (memory/memory-store))
        t (fw/write-file (str @tmp-dir) {:file-index i})
        out (json/parse-string
             (tool/invoke-tool t
                               {:path "noted.txt"
                                :content "indexed\n"
                                :create-dirs true}
                               dummy-ctx)
             true)
        path (.getPath (io/file @tmp-dir "noted.txt"))]
    (is (true? (:changed out)))
    (is (= (:sha256 out) (:sha256 (index/-lookup i path))))
    (is (= 1 (count (index/-edits i {:path path}))))
    (is (= "file_write" (:tool (first (index/-edits i {:path path})))))))
