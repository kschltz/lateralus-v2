(ns kschltz.agent.tools.file-safety-test
  "Tests for the kschltz.agent.tools.file-safety helpers."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.tools.file-safety :as fs])
  (:import [java.io File]
           [java.nio.file Files Path Paths]))

(def ^:private tmp-dir
  (delay
    (let [dir (File/createTempFile "lateralus-fs-safety-test" "")]
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

(defn- ^Path tmp-path
  "Resolve a Path under the per-test temp dir."
  [& parts]
  (.normalize (.toPath (apply io/file @tmp-dir parts))))

;; ---------------------------------------------------------------------------
;; within-write-dir? / blocked-path?
;; ---------------------------------------------------------------------------

(deftest within-write-dir-allows-nested-paths
  (let [root (tmp-path "wd")]
    (.mkdirs (.toFile root))
    (is (true? (fs/within-write-dir? root (.resolve root "a/b/c.txt"))))))

(deftest within-write-dir-rejects-parent-traversal
  (let [root (tmp-path "wd2")]
    (.mkdirs (.toFile root))
    ;; Java's Path.startsWith is segment-based but does not collapse
    ;; ".." segments; pre-normalization is what makes the containment
    ;; check meaningful. Confirm the impl rejects the normalized form.
    (let [normalized-traversal (.normalize (.toPath (io/file (.toString root)
                                                              ".." "escape.txt")))]
      (is (false? (fs/within-write-dir? root normalized-traversal))))))

(deftest within-write-dir-nil-write-dir-allows-everything
  (let [anywhere (.toPath (io/file "/tmp/lateralus-nil-wd"))]
    (is (true? (fs/within-write-dir? nil anywhere)))
    (is (true? (fs/within-write-dir? (.toPath (io/file "")) anywhere)))))

(deftest blocked-path-detects-blocked-segments
  (let [root (tmp-path "broot")]
    (.mkdirs (.toFile root))
    (is (true? (fs/blocked-path? (.resolve root ".git/config"))))
    (is (true? (fs/blocked-path? (.resolve root "node_modules/foo/bar.js"))))
    (is (true? (fs/blocked-path? (.resolve root "target/classes/a.class"))))
    (is (false? (fs/blocked-path? (.resolve root "src/foo.clj"))))))

(deftest blocked-path-accepts-custom-blocked-set
  (let [root (tmp-path "broot2")]
    (.mkdirs (.toFile root))
    (is (false? (fs/blocked-path? (.resolve root ".git/x") #{"node_modules"})))
    (is (true? (fs/blocked-path? (.resolve root "node_modules/x") #{"node_modules"})))))

(deftest clojure-file-detects-extensions-case-insensitively
  (let [root (tmp-path "croots")]
    (.mkdirs (.toFile root))
    (is (every? true?
                [(fs/clojure-file? (.resolve root "a.clj"))
                 (fs/clojure-file? (.resolve root "b.cljs"))
                 (fs/clojure-file? (.resolve root "c.cljc"))
                 (fs/clojure-file? (.resolve root "d.cljd"))
                 (fs/clojure-file? (.resolve root "e.edn"))
                 (fs/clojure-file? (.resolve root "UPPER.CLJ"))]))
    (is (false? (fs/clojure-file? (.resolve root "foo.txt"))))
    (is (false? (fs/clojure-file? (.resolve root "bar"))))))

;; ---------------------------------------------------------------------------
;; make-backup! / restore! / sha256 / staleness sentinel
;; ---------------------------------------------------------------------------

(deftest make-backup-creates-timestamped-sidecar
  (let [target (tmp-path "backup-target.txt")
        bak    (File/createTempFile "pre-existing-backup" ".bak.0")]
    ;; Make a same-basename sidecar to force the counter to bump.
    (.delete bak)
    (spit (.toFile target) "original")
    (let [b1 (fs/make-backup! target)
          _  (Thread/sleep 2)
          b2 (fs/make-backup! target)]
      (is (string? b1))
      (is (string? b2))
      (is (not= b1 b2))
      (is (re-find #"\.bak\.\d+$" b1))
      (is (= "original" (slurp (io/file b1))))
      (is (= "original" (slurp (io/file b2)))))))

(deftest make-backup-nil-for-missing-file
  (let [ghost (tmp-path "ghost.txt")]
    (is (nil? (fs/make-backup! ghost)))))

(deftest restore-copies-backup-bytes
  (let [target (tmp-path "restore.txt")
        bak    (File/createTempFile "restore-backup-" ".bak.1234")]
    (.delete bak)
    (spit (.toFile target) "original")
    (let [backup-path (fs/make-backup! target)]
      (spit (.toFile target) "MUTATED")
      (fs/restore! target backup-path)
      (is (= "original" (slurp (.toFile target)))))))

(deftest sha256-of-empty-and-abc
  ;; SHA-256("") and SHA-256("abc") well-known vectors.
  (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
         (fs/sha256 (.getBytes "" "UTF-8"))))
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
         (fs/sha256 (.getBytes "abc" "UTF-8")))))

(deftest staleness-sentinel-and-check-roundtrip
  (let [target (tmp-path "sentinel.txt")]
    (spit (.toFile target) "hello")
    (let [bytes (Files/readAllBytes target)
          sent  (fs/staleness-sentinel target bytes)]
      (is (false? (fs/check-staleness target sent)))
      (Thread/sleep 5)
      (spit (.toFile target) "hello") ;; same content but mtime changes
      (is (true? (fs/check-staleness target sent)))
      (Thread/sleep 5)
      (spit (.toFile target) "world")
      (is (true? (fs/check-staleness target sent))))))

(deftest check-staleness-treats-missing-file-as-stale
  (let [target (tmp-path "vanish.txt")
        sent   {:mtime 0 :size 0 :sha256 ""}]
    (is (true? (fs/check-staleness target sent)))))

;; ---------------------------------------------------------------------------
;; write-atomically!
;; ---------------------------------------------------------------------------

(deftest write-atomically-lands-content-and-cleans-temp
  (let [target (tmp-path "atomic.txt")]
    (fs/write-atomically! target "alpha\n")
    (is (= "alpha\n" (slurp (.toFile target))))
    ;; No leftover temp sidecar.
    (let [parent (.toFile (.getParent target))
          leftover (filter #(and (.startsWith (.getName ^File %)
                                             (str (.getName (.toFile target))
                                                  ".lateralus-write-"))
                                (.isFile ^File %))
                           (some-> (.listFiles parent) seq))]
      (is (empty? leftover)))))

(deftest write-atomically-overwrites-existing-content
  (let [target (tmp-path "atomic-overwrite.txt")]
    (spit (.toFile target) "old")
    (fs/write-atomically! target "new")
    (is (= "new" (slurp (.toFile target))))))

;; ---------------------------------------------------------------------------
;; normalize-for-match
;; ---------------------------------------------------------------------------

(deftest normalize-for-match-canonicalizes-eol-bom-and-punctuation
  (let [s      (str (char 0xFEFF) "foo \u201Cbar\u201D \u2013 baz\u00A0qux")
        result (fs/normalize-for-match s)]
    (is (true? (:bom? result)))
    (is (= :lf (:eol-style result)) "no CRLF present, so :lf")
    (is (= "foo \"bar\" - baz qux" (:normalized result)))))

(deftest normalize-for-match-detects-crlf-style
  (let [s      "line1\r\nline2\r\n"
        result (fs/normalize-for-match s)]
    (is (= :crlf (:eol-style result)))
    (is (false? (:bom? result)))
    ;; LF collapsed, no trailing-whitespace mangling beyond per-line.
    (is (= "line1\nline2" (:normalized result)))))

(deftest normalize-for-match-handles-nbsp-narrow-nbsp
  (let [s      (str "a" (char 0x202F) "b" (char 0x00A0) "c")
        result (fs/normalize-for-match s)]
    (is (= "a b c" (:normalized result)))))

;; ---------------------------------------------------------------------------
;; find-matches
;; ---------------------------------------------------------------------------

(deftest find-matches-exact-multiple
  (let [hay "foo foo foo"
        res (fs/find-matches hay "foo" false)]
    (is (false? (:fuzzy-fired? res)))
    (is (= 3 (count (:matches res))))
    (is (= [[0 3] [4 7] [8 11]]
           (mapv (juxt :start :end) (:matches res))))))

(deftest find-matches-fuzzy-on-eol-runs
  ;; The fuzzy path normalizes both haystack and needle to LF; when
  ;; only the EOL style differs the normalized form collides and the
  ;; match is reported with :fuzzy? true.
  (let [hay "alpha\r\nbeta\r\n"
        res (fs/find-matches hay "alpha\nbeta" true)]
    (is (true? (:fuzzy-fired? res)))
    (is (= 1 (count (:matches res))))
    (is (true? (:fuzzy? (first (:matches res)))))))

(deftest find-matches-fuzzy-disabled-no-exact-hit
  (let [hay (str "foo \u201Cbar\u201D baz")
        res (fs/find-matches hay "\"bar\"" false)]
    (is (false? (:fuzzy-fired? res)))
    (is (empty? (:matches res)))))

(deftest find-matches-no-match-returns-empty
  (let [hay "abcdef"
        res (fs/find-matches hay "zzz" true)]
    (is (false? (:fuzzy-fired? res)))
    (is (empty? (:matches res)))))

;; ---------------------------------------------------------------------------
;; scan-omission-placeholders / scan-line-number-prefixes
;; ---------------------------------------------------------------------------

(deftest scan-omission-placeholders-detects-known-markers
  (is (some? (fs/scan-omission-placeholders "// ... existing code ...")))
  (is (some? (fs/scan-omission-placeholders "... existing code ...")))
  (is (some? (fs/scan-omission-placeholders "<...>")))
  (is (some? (fs/scan-omission-placeholders "<TODO>")))
  (is (some? (fs/scan-omission-placeholders "<placeholder>")))
  (is (some? (fs/scan-omission-placeholders "# ... existing ...")))
  (is (nil? (fs/scan-omission-placeholders "(defn real [] 1)")))
  (is (nil? (fs/scan-omission-placeholders ""))))

(deftest scan-line-number-prefixes-detects-pasted-numbering
  (is (some? (fs/scan-line-number-prefixes "12:foo")))
  (is (some? (fs/scan-line-number-prefixes "1 | bar")))
  (is (some? (fs/scan-line-number-prefixes "  42 baz")))
  (is (nil? (fs/scan-line-number-prefixes "foo 12 bar")))
  (is (nil? (fs/scan-line-number-prefixes "normal code line"))))

;; ---------------------------------------------------------------------------
;; clojure-round-trip?
;; ---------------------------------------------------------------------------

(deftest clojure-round-trip-ok-for-valid-code
  (is (= {:ok true} (fs/clojure-round-trip? "(ns a) (defn b [] 1)"))))

(deftest clojure-round-trip-not-ok-for-malformed
  (let [r (fs/clojure-round-trip? "(defn x [)")]
    (is (false? (:ok r)))
    (is (string? (:reason r)))))

;; ---------------------------------------------------------------------------
;; with-path-lock macro
;; ---------------------------------------------------------------------------

(deftest with-path-lock-serializes-per-path
  (let [path-str "fake-path-string"
        order    (atom [])
        done1    (promise)
        done2    (promise)]
    (future
      (fs/with-path-lock path-str
        (swap! order conj :start-1)
        (Thread/sleep 25)
        (swap! order conj :end-1)
        (deliver done1 :done1)))
    (Thread/sleep 5)
    (future
      (fs/with-path-lock path-str
        (swap! order conj :start-2)
        (swap! order conj :end-2)
        (deliver done2 :done2)))
    (deref done1 2000 :timeout)
    (deref done2 2000 :timeout)
    ;; Lock is per-path: 1 must fully complete before 2 starts.
    (is (= [:start-1 :end-1 :start-2 :end-2] @order))))

(deftest find-matches-fuzzy-on-smart-quotes
  ;; The fuzzy window scan normalizes each haystack window, so a
  ;; needle with ASCII quotes matches a haystack with curly quotes.
  (let [hay (str "foo \u2018bar\u2019 baz \u2018bar\u2019 end")
        res (fs/find-matches hay "'bar'" true)]
    (is (true? (:fuzzy-fired? res)) "fuzzy path must fire for smart-quote deltas")
    (is (= 2 (count (:matches res))) "both occurrences of 'bar' must match")
    (is (every? :fuzzy? (:matches res)))))
