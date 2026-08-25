(ns kschltz.agent.tools.clojure-test
  "Tests for the structured Clojure editing Tool implementations."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.clojure :as tools.clojure])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private tmp-dir
  "Temporary directory for Clojure tool tests."
  (delay
    (let [dir (File/createTempFile "lateralus-clojure-test" "")]
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

(defn- temp-file
  "Create a temp file under `tmp-dir` with `content`."
  [name content]
  (let [f (io/file @tmp-dir name)]
    (io/make-parents f)
    (spit f content)
    (.deleteOnExit f)
    f))

(def ^:private dummy-ctx {})

(defn- invoke
  "Invoke a tool by keyword name and parse the JSON result."
  [registry name args]
  (json/parse-string (tool/invoke-tool (get registry name) args dummy-ctx) true))

(deftest clojure-registry-contains-seven-tools
  (let [registry (tools.clojure/clojure-registry)]
    (is (= 7 (count registry)))
    (is (contains? registry "clojure_query"))
    (is (contains? registry "clojure_add_require"))
    (is (contains? registry "clojure_remove_def"))
    (is (contains? registry "clojure_rename_symbol"))
    (is (contains? registry "clojure_insert_form"))
    (is (contains? registry "clojure_edit_def"))
    (is (contains? registry "clojure_format_file"))
    (is (every? tool/tool? (vals registry)))))

(deftest query-lists-defs-and-requires
  (temp-file "sample.clj" "(ns sample (:require [clojure.string :as str]))\n(defn foo [] 1)\n(def bar 2)")
  (let [reg    (tools.clojure/clojure-registry {:workspace-root (str @tmp-dir)})
        parsed (invoke reg "clojure_query" {:path "sample.clj"})]
    (is (vector? (:defs parsed)))
    (is (some #(= "foo" %) (:defs parsed)))
    (is (some #(= "bar" %) (:defs parsed)))
    (is (some #(str/includes? % "clojure.string") (:requires parsed)))))

(deftest add-require-appends-new-libspec
  (temp-file "sample.clj" "(ns sample (:require [clojure.string :as str]))\n(defn foo [] 1)")
  (let [reg    (tools.clojure/clojure-registry {:workspace-root (str @tmp-dir)})
        parsed (invoke reg "clojure_add_require" {:path "sample.clj" :libspec "clojure.set"})]
    (is (:changed parsed))
    (is (str/includes? (slurp (io/file @tmp-dir "sample.clj")) "clojure.set"))))

(deftest add-require-is-idempotent
  (temp-file "sample.clj" "(ns sample (:require [clojure.string :as str]))\n(defn foo [] 1)")
  (let [reg    (tools.clojure/clojure-registry {:workspace-root (str @tmp-dir)})
        parsed (invoke reg "clojure_add_require" {:path "sample.clj" :libspec "clojure.string"})]
    (is (not (:changed parsed)))))

(deftest add-require-creates-section-without-existing-requires
  (temp-file "sample.clj" "(ns sample)\n(defn foo [] 1)")
  (let [reg     (tools.clojure/clojure-registry {:workspace-root (str @tmp-dir)})
        parsed  (invoke reg "clojure_add_require" {:path "sample.clj" :libspec "clojure.string" :alias "str"})
        content (slurp (io/file @tmp-dir "sample.clj"))]
    (is (:changed parsed))
    (is (str/includes? content "(:require"))
    (is (str/includes? content "[clojure.string :as str]"))
    (is (some? (re-find #"\(:require\s+\[clojure\.string\s+:as\s+str\]\)" content)))))

(deftest remove-def-deletes-definition
  (temp-file "sample.clj" "(ns sample)\n(defn foo [] 1)\n(def bar 2)")
  (let [reg    (tools.clojure/clojure-registry {:workspace-root (str @tmp-dir)})
        parsed (invoke reg "clojure_remove_def" {:path "sample.clj" :name "foo"})
        content (slurp (io/file @tmp-dir "sample.clj"))]
    (is (:changed parsed))
    (is (not (str/includes? content "(defn foo")))
    (is (str/includes? content "(def bar"))))

(deftest remove-def-missing-definition
  (temp-file "sample.clj" "(ns sample)\n(defn foo [] 1)")
  (let [reg    (tools.clojure/clojure-registry {:workspace-root (str @tmp-dir)})
        parsed (invoke reg "clojure_remove_def" {:path "sample.clj" :name "bar"})]
    (is (not (:changed parsed)))
    (is (= "definition not found" (:reason parsed)))))

(deftest rename-symbol-replaces-occurrences
  (temp-file "sample.clj" "(ns sample)\n(defn foo [x] (foo x))\n(def bar (foo 1))")
  (let [reg     (tools.clojure/clojure-registry {:workspace-root (str @tmp-dir)})
        parsed  (invoke reg "clojure_rename_symbol" {:path "sample.clj" :old "foo" :new "qux"})
        content (slurp (io/file @tmp-dir "sample.clj"))]
    (is (:changed parsed))
    (is (= 3 (:renamed parsed)))
    (is (not (str/includes? content "foo")))
    (is (str/includes? content "qux"))))

(deftest rename-symbol-missing-symbol
  (temp-file "sample.clj" "(ns sample)\n(defn foo [] 1)")
  (let [reg    (tools.clojure/clojure-registry {:workspace-root (str @tmp-dir)})
        parsed (invoke reg "clojure_rename_symbol" {:path "sample.clj" :old "bar" :new "qux"})]
    (is (not (:changed parsed)))
    (is (= "symbol not found" (:reason parsed)))))

(deftest insert-form-at-end
  (temp-file "sample.clj" "(ns sample)\n(defn foo [] 1)")
  (let [reg    (tools.clojure/clojure-registry {:workspace-root (str @tmp-dir)})
        parsed (invoke reg "clojure_insert_form" {:path "sample.clj" :form "(def bar 2)" :position :end})]
    (is (:changed parsed))
    (is (str/includes? (slurp (io/file @tmp-dir "sample.clj")) "(def bar 2)"))))

(deftest insert-form-at-beginning
  (temp-file "sample.clj" "(ns sample)\n(defn foo [] 1)")
  (let [reg    (tools.clojure/clojure-registry {:workspace-root (str @tmp-dir)})
        parsed (invoke reg "clojure_insert_form" {:path "sample.clj" :form "(def bar 2)" :position :beginning})]
    (is (:changed parsed))
    (is (str/includes? (slurp (io/file @tmp-dir "sample.clj")) "(def bar 2)"))))

(deftest edit-def-replaces-body
  (temp-file "sample.clj" "(ns sample)\n(defn foo [] 1)")
  (let [reg    (tools.clojure/clojure-registry {:workspace-root (str @tmp-dir)})
        parsed (invoke reg "clojure_edit_def" {:path "sample.clj" :name "foo" :body "(inc 1)"})]
    (is (:changed parsed))
    (is (str/includes? (slurp (io/file @tmp-dir "sample.clj")) "(defn foo [] (inc 1))"))))

(deftest edit-def-preserves-docstring
  (temp-file "sample.clj" "(ns sample)\n(defn foo \"docs\" [] 1)")
  (let [reg     (tools.clojure/clojure-registry {:workspace-root (str @tmp-dir)})
        parsed  (invoke reg "clojure_edit_def" {:path "sample.clj" :name "foo" :body "(inc 1)"})
        content (slurp (io/file @tmp-dir "sample.clj"))]
    (is (:changed parsed))
    (is (str/includes? content "\"docs\""))
    (is (str/includes? content "(inc 1)"))))

(deftest format-file-works
  (temp-file "sample.clj" "(ns sample)\n(defn foo [] 1)")
  (let [reg    (tools.clojure/clojure-registry {:workspace-root (str @tmp-dir)})
        parsed (invoke reg "clojure_format_file" {:path "sample.clj"})]
    (is (contains? parsed :changed))))

(deftest write-creates-backup
  (temp-file "sample.clj" "(ns sample)\n(defn foo [] 1)")
  (let [reg (tools.clojure/clojure-registry {:workspace-root (str @tmp-dir)})
        result (invoke reg "clojure_remove_def" {:path "sample.clj" :name "foo"})
        backup (io/file (:backup-path result))]
    (is (.exists backup))
    (is (re-find #"\.bak\.\d+$" (.getName backup)))
    (is (= "(ns sample)\n(defn foo [] 1)" (slurp backup)))
    (is (string? (:previous-sha256 result)))
    (is (string? (:sha256 result)))))

(deftest malformed-edit-is-rejected
  (temp-file "sample.clj" "(ns sample)\n(defn foo [] 1)")
  (let [reg     (tools.clojure/clojure-registry {:workspace-root (str @tmp-dir)})
        result  (invoke reg "clojure_insert_form"
                        {:path "sample.clj" :form "(def" :position :end})
        content (slurp (io/file @tmp-dir "sample.clj"))]
    (is (false? (:ok result)))
    (is (= "clojure-tool-error" (:error result)))
    (is (= "(ns sample)\n(defn foo [] 1)" content))))

(deftest missing-file-returns-error
  (let [reg    (tools.clojure/clojure-registry {:workspace-root (str @tmp-dir)})
        result (invoke reg "clojure_query" {:path "missing.clj"})]
    (is (false? (:ok result)))
    (is (= "clojure-tool-error" (:error result)))))

(deftest clojure-tools-enforce-workspace-and-path-policy
  (let [reg (tools.clojure/clojure-registry {:workspace-root (str @tmp-dir)})]
    (testing "absolute paths outside the workspace are rejected"
      (let [outside (File/createTempFile "lateralus-clj-outside-" ".clj")
            _ (spit outside "(ns outside)")
            result (invoke reg "clojure_query" {:path (.getAbsolutePath outside)})]
        (is (= "outside-workspace" (:error result)))
        (.delete outside)))
    (testing "blocked path segments are rejected for reads and writes"
      (temp-file "target/generated.clj" "(ns generated)")
      (let [result (invoke reg "clojure_remove_def"
                           {:path "target/generated.clj" :name "x"})]
        (is (= "blocked-path" (:error result)))
        (is (= "(ns generated)"
               (slurp (io/file @tmp-dir "target/generated.clj"))))))
    (testing "non-Clojure files are routed to the generic file tools"
      (temp-file "notes.txt" "plain text")
      (let [result (invoke reg "clojure_query" {:path "notes.txt"})]
        (is (= "wrong-file-type" (:error result)))
        (is (= "file_read/file_update" (:use-tool result)))))))

(deftest clojure-tools-reject-symlink-escape
  (let [outside-dir (doto (io/file (System/getProperty "java.io.tmpdir")
                                   (str "lateralus-clj-link-" (random-uuid)))
                      (.mkdirs))
        outside-file (io/file outside-dir "outside.clj")
        _ (spit outside-file "(ns outside)\n(def x 1)")
        link (io/file @tmp-dir "escape")
        _ (Files/createSymbolicLink (.toPath link)
                                    (.toPath outside-dir)
                                    (make-array FileAttribute 0))
        reg (tools.clojure/clojure-registry {:workspace-root (str @tmp-dir)})
        result (invoke reg "clojure_remove_def"
                       {:path "escape/outside.clj" :name "x"})]
    (is (= "outside-workspace" (:error result)))
    (is (= "(ns outside)\n(def x 1)" (slurp outside-file)))
    (.delete link)
    (.delete outside-file)
    (.delete outside-dir)))
