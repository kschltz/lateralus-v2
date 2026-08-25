(ns kschltz.agent.tools.clojure-impl-test
  "Tests for the internal Clojure tool helpers."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.tools.clojure-impl :as impl]
            [rewrite-clj.zip :as z])
  (:import [java.io File]))

(def ^:private tmp-dir
  (delay
    (let [dir (File/createTempFile "lateralus-clojure-impl-test" "")]
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

(defn- temp-file [name content]
  (let [f (io/file @tmp-dir name)]
    (io/make-parents f)
    (spit f content)
    (.deleteOnExit f)
    f))

(deftest parse-or-fail-parses-multiple-forms
  (let [zloc (impl/parse-or-fail "(ns a)\n(defn b [] 1)\n(def c 2)" "path")]
    (is (= 3 (count (impl/top-level-forms zloc))))))

(deftest ns-form-finds-ns
  (let [zloc (impl/parse-or-fail "(ns a)\n(defn b [] 1)" "path")]
    (is (some? (impl/ns-form zloc)))))

(deftest find-top-level-def-finds-defn
  (let [zloc (impl/parse-or-fail "(ns a)\n(defn b [] 1)\n(def c 2)" "path")]
    (is (some? (impl/find-top-level-def zloc 'b)))
    (is (some? (impl/find-top-level-def zloc 'c)))
    (is (nil? (impl/find-top-level-def zloc 'd)))))

(deftest find-keyword-child-finds-require
  (let [zloc (impl/parse-or-fail "(ns a (:require [b :as c]))" "path")
        ns-zloc (impl/ns-form zloc)
        require-kw (impl/find-keyword-child ns-zloc :require)]
    (is (some? require-kw))
    (is (= :require (z/sexpr require-kw)))
    (is (= 1 (count (impl/libspecs-from-section (z/right require-kw)))))))

(deftest require-exists-matches-vector-libspec
  (let [zloc (impl/parse-or-fail "(ns a (:require [b :as c]))" "path")
        ns-zloc (impl/ns-form zloc)
        require-kw (impl/find-keyword-child ns-zloc :require)]
    (is (impl/require-exists? (z/right require-kw) 'b))
    (is (not (impl/require-exists? (z/right require-kw) 'x)))))

(deftest first-body-node-skips-docstring-and-vector
  (let [zloc (impl/parse-or-fail "(ns a)\n(defn b \"docs\" [x] (+ x 1))" "path")
        def-zloc (impl/find-top-level-def zloc 'b)
        body-zloc (impl/first-body-node def-zloc)]
    (is (= :list (z/tag body-zloc)))
    (is (= '(+ x 1) (z/sexpr body-zloc)))))

(deftest write-with-backup-creates-sidecar
  (temp-file "sample.clj" "(ns a)")
  (let [path (impl/resolve-path (str @tmp-dir) "sample.clj")]
    (let [result (impl/write-with-backup! path "(ns a)" "(ns b)")
          backup (io/file (:backup-path result))]
      (is (.exists backup))
      (is (re-find #"\.bak\.\d+$" (.getName backup)))
      (is (= "(ns a)" (slurp backup)))
      (is (= "(ns b)" (slurp (io/file @tmp-dir "sample.clj"))))
      (is (string? (:previous-sha256 result)))
      (is (string? (:sha256 result))))))

(deftest write-with-backup-rejects-stale-original
  (temp-file "stale.clj" "(ns current)")
  (let [path (impl/resolve-path (str @tmp-dir) "stale.clj")]
    (try
      (impl/write-with-backup! path "(ns observed)" "(ns replacement)")
      (is false "stale write should throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :stale-file (:error (ex-data e))))
        (is (= "(ns current)" (slurp (.toFile path))))
        (is (empty?
             (filter #(re-find #"\.bak\.\d+$" (.getName ^File %))
                     (file-seq @tmp-dir))))))))

(deftest malformed-source-throws
  (is (thrown? Exception (impl/parse-or-fail "(def" "path"))))

(deftest root-string-or-fail-round-trips
  (let [zloc (impl/parse-or-fail "(def a 1)" "path")]
    (is (= "(def a 1)" (impl/root-string-or-fail zloc "path")))))
