(ns kschltz.agent.tools.filesystem-test
  "Tests for the read-only filesystem Tool implementations."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.filesystem :as tools.filesystem])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

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

(deftest filesystem-registry-contains-eight-tools
  (testing "filesystem-registry returns the filesystem tools"
    (let [registry (tools.filesystem/filesystem-registry)]
      (is (= 8 (count registry)))
      (is (contains? registry "file_read"))
      (is (contains? registry "file_list"))
      (is (contains? registry "file_info"))
      (is (contains? registry "file_create"))
      (is (contains? registry "file_search"))
      (is (contains? registry "file_glob"))
      (is (contains? registry "file_write"))
      (is (contains? registry "file_update"))
      (is (every? tool/tool? (vals registry))))))

(deftest file-create-writes-content-and-parents
  (testing "file_create writes content and creates parent directories"
    (let [reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_create")
                                  {:path "nested/dir/test.txt" :content "created"}
                                  dummy-ctx)
          parsed (json/parse-string result true)]
      (is (:created parsed))
      (is (= "created" (slurp (io/file @tmp-dir "nested/dir/test.txt")))))))

(deftest file-create-is-safe-and-create-only
  (testing "file_create refuses to overwrite an existing file"
    (let [target (temp-file "existing.txt" "original")
          reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_create")
                                   {:path "existing.txt" :content "replacement"}
                                   dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= "file-exists" (:error parsed)))
      (is (= "original" (slurp target)))))
  (testing "file_create refuses blocked paths"
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_create")
                                   {:path ".git/config" :content "nope"}
                                   dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= "blocked-path" (:error parsed)))
      (is (not (.exists (io/file @tmp-dir ".git/config"))))))
  (testing "file_create refuses paths outside the workspace"
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          outside (File/createTempFile "lateralus-create-outside-" ".txt")
          path (.getAbsolutePath outside)
          _ (.delete outside)
          result (tool/invoke-tool (get reg "file_create")
                                   {:path path :content "nope"}
                                   dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= "outside-write-dir" (:error parsed)))
      (is (not (.exists outside))))))

(deftest file-read-returns-content
  (testing "file_read returns the UTF-8 content of a text file with line numbers"
    (let [f    (temp-file "hello.txt" "hello world")
          reg  (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_read") {:path "hello.txt"} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (map? parsed))
      (is (= "     1\thello world" (:content parsed)))
      (is (str/includes? (:content parsed) "hello world"))
      (is (= 11 (:size parsed)))
      (is (= "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
             (:sha256 parsed)))
      (is (= 1 (:total-lines parsed)))
      (is (= 1 (:offset parsed)))
      (is (= 2000 (:limit parsed)))
      (is (= 1 (:lines-returned parsed)))
      (is (false? (:truncated parsed)))
      (is (not (str/includes? (:content parsed) "[file-window:"))))))

(deftest file-read-honors-offset-and-limit
  (testing "file_read honors 1-based line offset and line limit"
    (let [f    (temp-file "lines.txt" "line1\nline2\nline3\nline4\nline5\n")
          reg  (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_read") {:path "lines.txt"
                                                          :offset 2
                                                          :limit 2} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (str/starts-with? (:content parsed) "     2\tline2\n     3\tline3"))
      (is (= 2 (:offset parsed)))
      (is (= 2 (:limit parsed)))
      (is (= 2 (:lines-returned parsed)))
      (is (= 5 (:total-lines parsed)))
      (is (true? (:truncated parsed)) "more lines remain beyond the window")
      (is (str/ends-with? (:content parsed)
                          (format "[file-window: %s lines 2-3 of 5; call file_read again with offset=4 to continue]"
                                  (:path parsed))))))
  (testing "file_read with no offset/limit returns the whole file untruncated"
    (let [f    (temp-file "lines.txt" "line1\nline2\nline3\nline4\nline5\n")
          reg  (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_read") {:path "lines.txt"} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= 5 (:lines-returned parsed)))
      (is (= 5 (:total-lines parsed)))
      (is (false? (:truncated parsed)))
      (is (not (str/includes? (:content parsed) "[file-window:")))
      (is (str/starts-with? (:content parsed) "     1\tline1")))))

(deftest file-read-errors-on-missing-file
  (testing "file_read returns a model-visible error for a missing file"
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (-> (tool/invoke-tool (get reg "file_read")
                                       {:path "does-not-exist.txt"}
                                       dummy-ctx)
                     (json/parse-string true))]
      (is (false? (:ok result)))
      (is (= "filesystem-error" (:error result)))
      (is (string? (:message result))))))

(deftest file-read-reports-binary-file
  (testing "file_read reports a binary file as a structured :error rather than throwing"
    (let [f   (io/file @tmp-dir "bin.dat")
          _   (io/make-parents f)
          _   (with-open [in (java.io.ByteArrayInputStream. (byte-array (map byte [0x00 0x41 0x42])))
                          out (io/output-stream f)]
                (io/copy in out))
          _   (.deleteOnExit f)
          reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_read") {:path "bin.dat"} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (not (str/starts-with? result "Filesystem tool error:")))
      (is (map? parsed))
      (is (= "binary-file" (:error parsed)))
      (is (string? (:path parsed)))
      (is (integer? (:size parsed))))))

(deftest file-read-offset-beyond-end
  (testing "file_read with offset past EOF returns empty content and no marker"
    (let [f   (temp-file "three.txt" "l1\nl2\nl3\n")
          reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_read") {:path "three.txt"
                                                          :offset 10
                                                          :limit 5} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= "" (:content parsed)))
      (is (= 0 (:lines-returned parsed)))
      (is (false? (:truncated parsed)))
      (is (= 3 (:total-lines parsed)))
      (is (not (str/includes? (:content parsed) "[file-window:"))))))

(deftest file-read-reports-total-lines
  (testing "file_read reports the real total line count for a known file"
    (let [f   (temp-file "six.txt" "a\nb\nc\nd\ne\nf\n")
          reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_read") {:path "six.txt"} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= 6 (:total-lines parsed)))
      (is (= 6 (:lines-returned parsed)))
      (is (false? (:truncated parsed))))))

(deftest file-read-truncation-marker-format
  (testing "file_read emits a well-formed continuation marker and resumes on the next call"
    (let [f   (temp-file "six.txt" "a\nb\nc\nd\ne\nf\n")
          reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          r1  (tool/invoke-tool (get reg "file_read") {:path "six.txt" :offset 1 :limit 3} dummy-ctx)
          p1  (json/parse-string r1 true)
          expected-path (:path p1)]
      (is (= 3 (:lines-returned p1)))
      (is (true? (:truncated p1)))
      (is (str/ends-with? (:content p1)
                          (format "[file-window: %s lines 1-3 of 6; call file_read again with offset=4 to continue]"
                                  expected-path))))
  (testing "the follow-up read returns the remaining lines with no marker"
    (let [_   (temp-file "six.txt" "a\nb\nc\nd\ne\nf\n")
          reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          r2  (tool/invoke-tool (get reg "file_read") {:path "six.txt" :offset 4 :limit 3} dummy-ctx)
          p2  (json/parse-string r2 true)]
      (is (= 3 (:lines-returned p2)))
      (is (= 6 (:total-lines p2)))
      (is (false? (:truncated p2)))
      (is (not (str/includes? (:content p2) "[file-window:")))
      (is (str/starts-with? (:content p2) "     4\td"))))))

(deftest file-read-file-window-marker
  (testing "file_read with a small limit ends with a continuation marker mentioning offset="
    (let [f   (temp-file "ten.txt" (str/join "\n" (map #(str "n" %) (range 1 11))))
          reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_read") {:path "ten.txt" :limit 4} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (true? (:truncated parsed)))
      (is (= 4 (:lines-returned parsed)))
      (is (str/ends-with? (:content parsed) " to continue]"))
      (is (str/includes? (:content parsed) "call file_read again with offset=")))))

(deftest file-list-returns-directory-entries
  (testing "file_list returns files and directories"
    (temp-file "a.txt" "a")
    (let [subdir (io/file @tmp-dir "sub")]
      (.mkdirs subdir)
      (.deleteOnExit subdir))
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_list") {:path "."} dummy-ctx)
          parsed (json/parse-string result true)
          entries (:entries parsed)]
      (is (vector? entries))
      (is (= 2 (count entries)))
      (is (= 2 (:total-entries parsed)))
      (is (false? (:truncated parsed)))
      (is (some #(and (= "a.txt" (:name %)) (= "file" (:type %))) entries))
      (is (some #(and (= "sub" (:name %)) (= "directory" (:type %))) entries)))))

(deftest file-list-is-bounded-and-deterministic
  (doseq [name ["z.txt" "a.txt" "m.txt"]]
    (temp-file name name))
  (let [reg (tools.filesystem/filesystem-registry
             {:workspace-root (str @tmp-dir)
              :max-list-entries 2})
        parsed (-> (tool/invoke-tool (get reg "file_list")
                                     {:path "."}
                                     dummy-ctx)
                   (json/parse-string true))]
    (is (= ["a.txt" "m.txt"] (mapv :name (:entries parsed))))
    (is (= 3 (:total-entries parsed)))
    (is (true? (:truncated parsed)))))

(deftest file-info-describes-path
  (testing "file_info returns metadata"
    (let [f   (temp-file "info.txt" "content")
          reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_info") {:path "info.txt"} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (true? (:exists parsed)))
      (is (= "file" (:type parsed)))
      (is (= 7 (:size parsed)))
      (is (integer? (:last-modified parsed))))))

(deftest file-info-reports-missing-file
  (testing "file_info reports a missing path"
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_info") {:path "missing.txt"} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (false? (:exists parsed))))))

(deftest file-search-finds-pattern
  (testing "file_search returns regex matches recursively"
    (temp-file "one.txt" "foo bar baz\nsecond line")
    (temp-file "two.txt" "FOO BAZ\n")
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_search") {:path "."
                                                            :pattern "baz"} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= 2 (count parsed)))
      (is (every? #(re-find #"(?i)baz" (:text %)) parsed))
      (is (every? #(contains? % :file) parsed))
      (is (every? #(contains? % :line) parsed)))))

(deftest file-search-respects-max-results
  (testing "file_search caps results at :max-results"
    (doseq [i (range 5)]
      (temp-file (str "match" i ".txt") (str "hit " i)))
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_search") {:path "."
                                                            :pattern "hit"
                                                            :max-results 3} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= 3 (count parsed))))))

(deftest file-search-errors-on-invalid-regex
  (testing "file_search returns an error for an invalid pattern"
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (-> (tool/invoke-tool (get reg "file_search")
                                       {:path "." :pattern "[invalid"}
                                       dummy-ctx)
                     (json/parse-string true))]
      (is (= "invalid-pattern" (:error result)))
      (is (string? (:message result))))))

(deftest read-tools-enforce-workspace-and-blocked-paths
  (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})]
    (testing "absolute paths outside the workspace are rejected"
      (let [outside (File/createTempFile "lateralus-read-outside-" ".txt")
            _ (spit outside "secret")
            parsed (-> (tool/invoke-tool (get reg "file_read")
                                         {:path (.getAbsolutePath outside)}
                                         dummy-ctx)
                       (json/parse-string true))]
        (is (= "outside-workspace" (:error parsed)))
        (.delete outside)))
    (testing "blocked paths are not readable or searchable"
      (temp-file ".git/secret.txt" "needle")
      (temp-file "src/visible.txt" "needle")
      (let [blocked (-> (tool/invoke-tool (get reg "file_read")
                                          {:path ".git/secret.txt"}
                                          dummy-ctx)
                        (json/parse-string true))
            hits (-> (tool/invoke-tool (get reg "file_search")
                                       {:path "." :pattern "needle"}
                                       dummy-ctx)
                     (json/parse-string true))]
        (is (= "blocked-path" (:error blocked)))
        (is (= 1 (count hits)))
        (is (str/ends-with? (:file (first hits)) "src/visible.txt"))))))

(deftest read-tools-reject-symlink-escape-unless-operator-allows-it
  (let [outside-dir (doto (io/file (System/getProperty "java.io.tmpdir")
                                   (str "lateralus-read-link-" (random-uuid)))
                      (.mkdirs))
        outside-file (io/file outside-dir "outside.txt")
        _ (spit outside-file "visible only by policy")
        link (io/file @tmp-dir "escape")
        _ (Files/createSymbolicLink (.toPath link)
                                    (.toPath outside-dir)
                                    (make-array FileAttribute 0))
        safe-reg (tools.filesystem/filesystem-registry
                  {:workspace-root (str @tmp-dir)})
        open-reg (tools.filesystem/filesystem-registry
                  {:workspace-root (str @tmp-dir)
                   :allow-read-outside-workspace? true})
        rejected (-> (tool/invoke-tool (get safe-reg "file_info")
                                       {:path "escape/outside.txt"}
                                       dummy-ctx)
                     (json/parse-string true))
        allowed (-> (tool/invoke-tool (get open-reg "file_read")
                                      {:path "escape/outside.txt"}
                                      dummy-ctx)
                    (json/parse-string true))]
    (is (= "outside-workspace" (:error rejected)))
    (is (str/includes? (:content allowed) "visible only by policy"))
    (.delete link)
    (.delete outside-file)
    (.delete outside-dir)))

(deftest custom-max-read-bytes-truncates-large-files
  (testing "a configured :max-read-bytes budget truncates a large file rather than erroring"
    (let [f   (temp-file "big.txt" (apply str (repeat 200 "x")))
          reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)
                                                    :max-read-bytes 100})
          result (tool/invoke-tool (get reg "file_read") {:path "big.txt"} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (map? parsed) "result must be JSON, not a Filesystem tool error string")
      (is (not (str/starts-with? result "Filesystem tool error:")))
      (is (= 200 (:size parsed)))
      (is (= 1 (:total-lines parsed)))
      (is (= 1 (:lines-returned parsed)) "the single oversized line is returned truncated-to-budget")
      (is (true? (:truncated parsed)))
      (is (str/includes? (:content parsed) "x"))
      (is (str/includes? (:content parsed) " (line truncated to "))
      (is (str/includes? (:content parsed) "[file-window:"))
      (is (str/includes? (:content parsed) "offset=2")))))

(deftest custom-max-search-results-caps-default
  (testing "a configured :max-search-results default is honored"
    (doseq [i (range 5)]
      (temp-file (str "hit" i ".txt") (str "match " i)))
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)
                                                    :max-search-results 2})
          result (tool/invoke-tool (get reg "file_search") {:path "."
                                                              :pattern "match"} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= 2 (count parsed))))))

(deftest file-write-creates-new-file-and-parents
  (testing "file_write creates missing parent directories and the file"
    (let [reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_write")
                                  {:path "deep/nested/written.txt"
                                   :content "hello world\n"
                                   :create-dirs true}
                                  dummy-ctx)
          parsed (json/parse-string result true)
          on-disk (slurp (io/file @tmp-dir "deep/nested/written.txt"))]
      (is (true? (:created parsed)))
      (is (true? (:changed parsed)))
      (is (nil? (:backup-path parsed)))
      (is (= "hello world\n" on-disk))
      (is (= (count (.getBytes "hello world\n" "UTF-8")) (:bytes-written parsed))))))

(deftest file-write-overwrites-and-backs-up
  (testing "file_write writes a timestamped .bak.<millis> sidecar of the prior contents"
    (let [target (temp-file "overwrite.txt" "old content\n")
          reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_write")
                                  {:path "overwrite.txt"
                                   :content "new content\n"}
                                  dummy-ctx)
          parsed (json/parse-string result true)
          backup (io/file (:backup-path parsed))]
      (is (true? (:changed parsed)))
      (is (string? (:backup-path parsed)))
      (is (.exists backup) "backup sidecar should exist")
      (is (re-find #"\.bak\.\d+$" (.getPath backup)))
      (is (= "old content\n" (slurp backup)))
      (is (= "new content\n" (slurp target))))))

(deftest file-write-rejects-omission-placeholder
  (testing "file_write refuses content containing an omission-placeholder"
    (let [target (temp-file "stub.txt" "real content\n")
          reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_write")
                                  {:path "stub.txt"
                                   :content "// ... existing code ..."}
                                  dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= "omission-placeholder" (:error parsed)))
      (is (= "real content\n" (slurp target)) "file must not be touched on rejection"))))

(deftest file-write-rejects-blocked-path
  (testing "file_write refuses to touch a blocked segment even with :force"
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_write")
                                  {:path ".git/hooks/pre-commit"
                                   :content "evil"
                                   :force true}
                                  dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= "blocked-path" (:error parsed)))
      (is (not (.exists (io/file @tmp-dir ".git")))))))

(deftest file-write-rejects-outside-workspace-without-force
  (testing "file_write refuses paths outside the workspace root"
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          outside (File/createTempFile "lateralus-outside-" ".txt")
          outside-path (.getAbsolutePath outside)
          _ (.delete outside)
          result (tool/invoke-tool (get reg "file_write")
                                  {:path outside-path :content "nope"}
                                  dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= "outside-write-dir" (:error parsed)))
      (is (not (.exists outside))))))

(deftest file-write-rejects-symlink-escape
  (testing "canonical containment prevents writes through a workspace symlink"
    (let [outside (doto (io/file (System/getProperty "java.io.tmpdir")
                                 (str "lateralus-symlink-outside-" (random-uuid)))
                    (.mkdirs))
          link (io/file @tmp-dir "escape")
          _ (Files/createSymbolicLink (.toPath link)
                                      (.toPath outside)
                                      (make-array FileAttribute 0))
          reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_write")
                                   {:path "escape/pwned.txt"
                                    :content "nope"
                                    :create-dirs true}
                                   dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= "outside-write-dir" (:error parsed)))
      (is (not (.exists (io/file outside "pwned.txt"))))
      (.delete link)
      (.delete outside))))

(deftest file-read-digest-fences-follow-up-write
  (testing "the read digest permits a fresh write and rejects a stale one"
    (let [target (temp-file "digest.txt" "v1\n")
          reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          read-result (-> (tool/invoke-tool (get reg "file_read")
                                            {:path "digest.txt"}
                                            dummy-ctx)
                          (json/parse-string true))
          digest (:sha256 read-result)
          fresh (-> (tool/invoke-tool (get reg "file_write")
                                      {:path "digest.txt"
                                       :content "v2\n"
                                       :expected-sha256 digest}
                                      dummy-ctx)
                    (json/parse-string true))]
      (is (true? (:changed fresh)))
      (spit target "external\n")
      (let [stale (-> (tool/invoke-tool (get reg "file_write")
                                        {:path "digest.txt"
                                         :content "v3\n"
                                         :expected-sha256 digest}
                                        dummy-ctx)
                      (json/parse-string true))]
        (is (= "stale-file" (:error stale)))
        (is (= digest (:expected-sha256 stale)))
        (is (string? (:actual-sha256 stale)))
        (is (= "external\n" (slurp target)))))))

(deftest file-write-honors-create-dirs-false
  (testing "file_write errors (does not silently mkdir) when :create-dirs is omitted"
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (-> (tool/invoke-tool (get reg "file_write")
                                       {:path "missing-dir/x.txt" :content "x"}
                                       dummy-ctx)
                     (json/parse-string true))]
      (is (= "filesystem-error" (:error result)))
      (is (string? (:message result)))
      (is (not (.exists (io/file @tmp-dir "missing-dir")))
          "no parent directory should have been created"))))

(deftest file-update-single-edit-applies
  (testing "file_update applies a single old->new edit and reports fuzzy-fired false"
    (let [target (temp-file "ed.txt" "AAA BBB CCC\n")
          reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_update")
                                  {:path "ed.txt"
                                   :edits [{:old-text "BBB" :new-text "X"}]}
                                  dummy-ctx)
          parsed (json/parse-string result true)]
      (is (true? (:changed parsed)))
      (is (= 1 (:edits-applied parsed)))
      (is (false? (:fuzzy-fired parsed)))
      (is (= "AAA X CCC\n" (slurp target)))
      (is (string? (:backup-path parsed))))))

(deftest file-update-multi-edit-reverse-position-order
  (testing "file_update applies multiple edits regardless of position order"
    (let [target (temp-file "ed2.txt" "AAA BBB CCC\n")
          reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_update")
                                  {:path "ed2.txt"
                                   :edits [{:old-text "AAA" :new-text "X"}
                                           {:old-text "CCC" :new-text "Z"}]}
                                  dummy-ctx)
          parsed (json/parse-string result true)]
      (is (true? (:changed parsed)))
      (is (= 2 (:edits-applied parsed)))
      (is (= "X BBB Z\n" (slurp target))))))

(deftest file-update-overlap-rejected
  (testing "file_update rejects overlapping old-text spans without writing"
    (let [target (temp-file "ed-overlap.txt" "abcdefgh\n")
          reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_update")
                                  {:path "ed-overlap.txt"
                                   :edits [{:old-text "abcd" :new-text "X"}
                                           {:old-text "cdef" :new-text "Y"}]}
                                  dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= "overlap" (:error parsed)))
      (is (= "abcdefgh\n" (slurp target)))
      (is (nil? (:backup-path parsed)) "no backup should be written on rejection"))))

(deftest file-update-no-op-rejected
  (testing "file_update rejects an edit where old-text == new-text"
    (let [target (temp-file "noop.txt" "hello\n")
          reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_update")
                                  {:path "noop.txt"
                                   :edits [{:old-text "hello" :new-text "hello"}]}
                                  dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= "no-op" (:error parsed)))
      (is (= "hello\n" (slurp target))))))

(deftest file-update-empty-old-text-rejected
  (testing "file_update rejects an edit with empty old-text"
    (let [target (temp-file "empty.txt" "hello\n")
          reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_update")
                                  {:path "empty.txt"
                                   :edits [{:old-text "" :new-text "X"}]}
                                  dummy-ctx)]
      ;; The empty :old-text is rejected at the Malli input-schema
      ;; boundary with min-length 1, so the tool never enters its body
      ;; and never writes the file.
      (is (string? result))
      (is (str/includes? result "input validation failed"))
      (is (str/includes? result "old-text"))
      (is (= "hello\n" (slurp target))))))

(deftest file-update-no-match-atomic-no-write
  (testing "file_update returns :no-match atomically (no backup, file unchanged)"
    (let [target (temp-file "nomatch.txt" "abc def\n")
          reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_update")
                                  {:path "nomatch.txt"
                                   :edits [{:old-text "zzz" :new-text "X"}]}
                                  dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= "no-match" (:error parsed)))
      (is (= "abc def\n" (slurp target)))
      (is (nil? (:backup-path parsed))))))

(deftest file-update-ambiguous-without-replace-all
  (testing "file_update refuses multiple matches when :replace-all is absent"
    (let [target (temp-file "ambig.txt" "foo foo foo\n")
          reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_update")
                                  {:path "ambig.txt"
                                   :edits [{:old-text "foo" :new-text "bar"}]}
                                  dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= "ambiguous-match" (:error parsed)))
      (is (= 3 (:count parsed)))
      (is (= "foo foo foo\n" (slurp target))))))

(deftest file-update-replace-all-replaces-every-occurrence
  (testing "file_update replaces every occurrence when :replace-all is true"
    (let [target (temp-file "repall.txt" "foo foo foo\n")
          reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_update")
                                  {:path "repall.txt"
                                   :edits [{:old-text "foo" :new-text "bar"
                                            :replace-all true}]}
                                  dummy-ctx)
          parsed (json/parse-string result true)]
      (is (true? (:changed parsed)))
      (is (= 3 (:edits-applied parsed)))
      (is (= "bar bar bar\n" (slurp target))))))

(deftest file-update-expected-occurrences-mismatch
  (testing "file_update rejects an :expected-occurrences count that does not match"
    ;; 5 occurrences; expect 3 with replace-all so we bypass the
    ;; ambiguous-match branch and reach the count check.
    (let [target (temp-file "count.txt" "foo foo foo foo foo\n")
          reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_update")
                                  {:path "count.txt"
                                   :edits [{:old-text "foo" :new-text "bar"
                                            :replace-all true
                                            :expected-occurrences 3}]}
                                  dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= "count-mismatch" (:error parsed)))
      (is (= 3 (:expected parsed)))
      (is (= 5 (:actual parsed)))
      (is (= "foo foo foo foo foo\n" (slurp target))))))

(deftest file-update-fuzzy-on-eol-mismatch
  (testing "file_update fuzzy-matches across CRLF/LF mismatches"
    (let [target (temp-file "eol.txt" "alpha\r\nbeta\r\ngamma\r\n")
          reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_update")
                                  {:path "eol.txt"
                                   :edits [{:old-text "alpha\nbeta" :new-text "FIRST"}]
                                   :fuzzy true}
                                  dummy-ctx)
          parsed (json/parse-string result true)]
      (is (true? (:changed parsed)) "EOL mismatch should resolve via fuzzy")
      (is (true? (:fuzzy-fired parsed)))
      (is (= "FIRST\r\ngamma\r\n" (slurp target)))))

  (testing "file_update without :fuzzy rejects the EOL mismatch"
    (let [target (temp-file "eol2.txt" "alpha\r\nbeta\r\n")
          reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_update")
                                  {:path "eol2.txt"
                                   :edits [{:old-text "alpha\nbeta" :new-text "FIRST"}]
                                   :fuzzy false}
                                  dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= "no-match" (:error parsed)))
      (is (= "alpha\r\nbeta\r\n" (slurp target))))))

(deftest file-update-missing-file
  (testing "file_update reports :file-not-found for a non-existent path"
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_update")
                                  {:path "no-such-file.txt"
                                   :edits [{:old-text "a" :new-text "b"}]}
                                  dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= "file-not-found" (:error parsed))))))

(deftest file-update-line-number-prefix-rejected
  (testing "file_update rejects a pasted old-text that carries a line-number prefix"
    (let [target (temp-file "numbered.txt" "alpha\nbeta\n")
          reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_update")
                                  {:path "numbered.txt"
                                   :edits [{:old-text "12:foo" :new-text "bar"}]}
                                  dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= "line-number-prefix" (:error parsed)))
      (is (= "alpha\nbeta\n" (slurp target))))))

(deftest file-update-stringified-edits-seam
  (testing "file_update accepts :edits as a JSON string and decodes it"
    (let [target (temp-file "seam.txt" "foo bar\n")
          reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_update")
                                  {:path "seam.txt"
                                   :edits "[{\"old-text\":\"foo\",\"new-text\":\"FOO\"}]"}
                                  dummy-ctx)
          parsed (json/parse-string result true)]
      (is (true? (:changed parsed)) (str "result=" result))
      (is (= 1 (:edits-applied parsed)))
      (is (= "FOO bar\n" (slurp target))))))

(deftest custom-max-search-file-bytes-skips-large-files
  (testing "a configured :max-search-file-bytes skips large files"
    (temp-file "small.txt" "needle")
    (temp-file "huge.txt" (apply str (repeat 500 "x")))
    (let [reg (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)
                                                    :max-search-file-bytes 100})
          result (tool/invoke-tool (get reg "file_search") {:path "."
                                                              :pattern "needle"} dummy-ctx)
          parsed (json/parse-string result true)]
      (is (= 1 (count parsed)))
      (is (str/ends-with? (:file (first parsed)) "small.txt")))))

(deftest file-update-fuzzy-on-smart-quotes
  (testing "file_update fuzzy-matches smart quotes in the file against an ASCII old-text"
    (let [target (temp-file "smart.txt"
                            (str "foo " (char 0x2018) "bar" (char 0x2019) " baz\n"))
          reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_update")
                                  {:path "smart.txt"
                                   :edits [{:old-text "'bar'"
                                            :new-text "'BAR'"}]
                                   :fuzzy true}
                                  dummy-ctx)
          parsed (json/parse-string result true)]
      (is (true? (:changed parsed)) (str "result=" result))
      (is (= 1 (:edits-applied parsed)))
      (is (true? (:fuzzy-fired parsed)) "smart-quote match must come from the fuzzy path")
      (is (= "foo 'BAR' baz\n" (slurp target))))))

(deftest file-update-preserves-bom-on-edit
  (testing "file_update edits a BOM-prefixed file and leaves exactly one leading BOM"
    (let [target (temp-file "bom.txt"
                            (str (char 0xFEFF) "foo bar baz\n"))
          reg    (tools.filesystem/filesystem-registry {:workspace-root (str @tmp-dir)})
          result (tool/invoke-tool (get reg "file_update")
                                  {:path "bom.txt"
                                   :edits [{:old-text "bar" :new-text "BAR"}]}
                                  dummy-ctx)
          parsed (json/parse-string result true)
          after  (slurp target)]
      (is (true? (:changed parsed)) (str "result=" result))
      (is (= (str (char 0xFEFF) "foo BAR baz\n") after)
          "result must retain exactly one leading BOM, not two")
      (is (= 1 (count (filter #(= % (char 0xFEFF)) after)))
          "exactly one U+FEFF in the file after the edit"))))
