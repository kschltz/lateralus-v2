(ns kschltz.agent.tools.file-path-test
  "Tests for the tiny shared path helpers in
   `kschltz.agent.tools.file-path`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tools.file-path :as fpath])
  (:import [java.io File]
           [java.nio.file Files Paths]))

(deftest workspace-root-to-file-uses-cwd-when-nil
  (testing "nil workspace-root falls back to the current working directory"
    (is (= (.getCanonicalFile (io/file "."))
           (.getCanonicalFile (fpath/workspace-root->file nil)))))
  (testing "empty-string workspace-root also falls back to cwd"
    (is (= (.getCanonicalFile (io/file "."))
           (.getCanonicalFile (fpath/workspace-root->file ""))))))

(deftest workspace-root-to-file-honors-string-root
  (testing "a non-empty string is used as the workspace root"
    (let [f (fpath/workspace-root->file "/tmp")]
      (is (instance? File f))
      (is (= "/tmp" (.getPath f))))))

(deftest resolve-path-returns-normalized-absolute
  (testing "absolute paths are preserved and normalized"
    (let [p (fpath/resolve-path nil "/tmp/foo/../bar/./baz.txt")]
      (is (instance? java.nio.file.Path p))
      (is (str/ends-with? (.toString p) "/tmp/bar/baz.txt")))))

(deftest resolve-path-joins-relative-against-root
  (testing "relative paths are resolved under the workspace root"
    (let [p (fpath/resolve-path "/tmp" "x/y/../z.txt")]
      (is (str/ends-with? (.toString p) "/tmp/x/z.txt")))))

(deftest path-to-str-normalizes-the-path
  (testing "path->str calls .normalize and round-trips via str"
    (let [raw (Paths/get "/tmp/a/../b" (into-array String []))
          s   (fpath/path->str raw)]
      (is (string? s))
      (is (str/ends-with? s "/tmp/b")))))
