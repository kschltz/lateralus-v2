(ns kschltz.agent.tools.filesystem-test
  "Tests for the read-only filesystem Tool implementations."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.filesystem :as tools.filesystem])
  (:import [java.io File]))

(def ^:private tmp-dir
  "Temporary directory for filesystem tool tests."
  (delay
    (let [dir (File/createTempFile "lateralus-fs-test" "")]
      (.delete dir)
      (.mkdirs dir)
      (.deleteOnExit dir)
      dir)))

(use-fixtures :each
  (fn [f]
    ;; Clean the temp dir between tests.
    (doseq [^File fseq (reverse (file-seq @tmp-dir))]
      (when (not= fseq @tmp-dir)
        (.delete fseq)))
    (f)))

(defn- temp-file
  "Create a temp file under `tmp-dir` with `content`."
  [name content]
  (let [f (io/file @tmp-dir name)]
    (io/make-parents f)
    (spit f content)
    (.deleteOnExit f)
    f))

(def ^:private dummy-ctx {})

(deftest filesystem-registry-contains-five-tools
  (testing "filesystem-registry returns the filesystem tools"
    (let [registry (tools.filesystem/filesystem-registry)]
      (is (= 5 (count registry)))
      (is (contains? registry "file/read"))
      (is (contains? registry "file/list"))
      (is (contains? registry "file/info"))
      (is (contains? registry "file/create"))
      (is (contains? registry "file/search"))
      (is (every? tool/tool? (vals registry))))))

(deftest file-create-writes-content-and-parents
  (testing "file/create writes content and creates parent directories"
    (let [reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file/create")
                                  {:path "nested/dir/test.txt" :content "created"}
                                  dummy-ctx)
          parsed (json/parse-string result true)]
      (is (:created parsed))
      (is (= "created" (slurp (io/file @tmp-dir "nested/dir/test.txt")))))))

(deftest file-read-returns-content
  (testing "file/read returns the UTF-8 content of a text file"
    (let [f    (temp-file "hello.txt" "hello world")
          reg  (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file/read") {:path "hello.txt"} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (map? parsed))
      (is (= "hello world" (:content parsed)))
      (is (= 11 (:size parsed))))))

(deftest file-read-honors-offset-and-limit
  (testing "file/read supports offset and limit"
    (let [f    (temp-file "abc.txt" "abcdefghij")
          reg  (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file/read") {:path "abc.txt"
                                                          :offset 2
                                                          :limit 3} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= "cde" (:content parsed))))))

(deftest file-read-errors-on-missing-file
  (testing "file/read returns a model-visible error for a missing file"
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file/read") {:path "does-not-exist.txt"} dummy-ctx)]
      (is (string? result))
      (is (str/starts-with? result "Filesystem tool error:")))))

(deftest file-list-returns-directory-entries
  (testing "file/list returns files and directories"
    (temp-file "a.txt" "a")
    (let [subdir (io/file @tmp-dir "sub")]
      (.mkdirs subdir)
      (.deleteOnExit subdir))
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file/list") {:path "."} dummy-ctx)
          parsed (json/parse-string result true)
          entries (:entries parsed)]
      (is (vector? entries))
      (is (= 2 (count entries)))
      (is (some #(and (= "a.txt" (:name %)) (= "file" (:type %))) entries))
      (is (some #(and (= "sub" (:name %)) (= "directory" (:type %))) entries)))))

(deftest file-info-describes-path
  (testing "file/info returns metadata"
    (let [f   (temp-file "info.txt" "content")
          reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file/info") {:path "info.txt"} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (true? (:exists parsed)))
      (is (= "file" (:type parsed)))
      (is (= 7 (:size parsed)))
      (is (integer? (:last-modified parsed))))))

(deftest file-info-reports-missing-file
  (testing "file/info reports a missing path"
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file/info") {:path "missing.txt"} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (false? (:exists parsed))))))

(deftest file-search-finds-pattern
  (testing "file/search returns regex matches recursively"
    (temp-file "one.txt" "foo bar baz\nsecond line")
    (temp-file "two.txt" "FOO BAZ\n")
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file/search") {:path "."
                                                            :pattern "baz"} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= 2 (count parsed)))
      (is (every? #(re-find #"(?i)baz" (:text %)) parsed))
      (is (every? #(contains? % :file) parsed))
      (is (every? #(contains? % :line) parsed)))))

(deftest file-search-respects-max-results
  (testing "file/search caps results at :max-results"
    (doseq [i (range 5)]
      (temp-file (str "match" i ".txt") (str "hit " i)))
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file/search") {:path "."
                                                            :pattern "hit"
                                                            :max-results 3} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= 3 (count parsed))))))

(deftest file-search-errors-on-invalid-regex
  (testing "file/search returns an error for an invalid pattern"
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file/search") {:path "."
                                                              :pattern "[invalid"} dummy-ctx)]
      (is (str/starts-with? result "Filesystem tool error:")))))

(deftest custom-max-read-bytes-rejects-large-files
  (testing "a configured :max-read-bytes limit is honored"
    (let [f   (temp-file "big.txt" (apply str (repeat 200 "x")))
          reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)
                                                    :max-read-bytes 100})]
      (is (str/starts-with?
           (tool/invoke-tool (get reg "file/read") {:path "big.txt"} dummy-ctx)
           "Filesystem tool error:")))))

(deftest custom-max-search-results-caps-default
  (testing "a configured :max-search-results default is honored"
    (doseq [i (range 5)]
      (temp-file (str "hit" i ".txt") (str "match " i)))
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)
                                                    :max-search-results 2})
          result (tool/invoke-tool (get reg "file/search") {:path "."
                                                              :pattern "match"} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= 2 (count parsed))))))

(deftest custom-max-search-file-bytes-skips-large-files
  (testing "a configured :max-search-file-bytes skips large files"
    (temp-file "small.txt" "needle")
    (temp-file "huge.txt" (apply str (repeat 500 "x")))
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)
                                                    :max-search-file-bytes 100})
          result (tool/invoke-tool (get reg "file/search") {:path "."
                                                              :pattern "needle"} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= 1 (count parsed)))
      (is (str/ends-with? (:file (first parsed)) "small.txt")))))
