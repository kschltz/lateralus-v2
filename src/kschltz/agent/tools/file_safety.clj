(ns kschltz.agent.tools.file-safety
  "Shared safety + edit helpers for filesystem and (future) clojure tools.

   This namespace collects the low-level, side-effecting and pure
   helpers that make write/edit tools safe to expose to an LLM:

     * path containment checks (`within-write-dir?`, `blocked-path?`)
     * Clojure-source detection (`clojure-file?`)
     * backup + restore of files before mutation (`make-backup!`,
       `restore!`)
     * content integrity via SHA-256 and a staleness sentinel
       (`sha256`, `staleness-sentinel`, `check-staleness`)
     * atomic on-disk writes (`write-atomically!`)
     * fuzzy text matching with EOL/BOM/Unicode normalization
       (`normalize-for-match`, `find-matches`)
     * omission-placeholder and line-number-prefix detection
       (`scan-omission-placeholders`, `scan-line-number-prefixes`)
     * lazy Clojure round-trip validation (`clojure-round-trip?`)
     * per-path locking (`with-path-lock`)

   The helpers are intentionally generic: they depend only on
   `cheshire`, the Clojure standard library, and a handful of JDK
   classes. `rewrite-clj` is required lazily inside
   `clojure-round-trip?` so that environments without rewrite-clj on
   the classpath can still use every other helper. This namespace does
   NOT import `kschltz.agent.tools.clojure-impl`; it mirrors that
   file's `write-with-backup!`/`resolve-path` pattern without coupling
   the generic tool layer to rewrite-clj."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path StandardCopyOption]
           [java.security MessageDigest]
           [java.text Normalizer Normalizer$Form]))

(def default-max-write-bytes
  "Default upper bound on how many bytes a write tool will accept.
   10 MiB is generous for source files while still catching runaway
   edits."
  (* 10 1024 1024))

(def default-blocked-paths
  "Path segments the write tools refuse to touch by default. These are
   version-control internals and build/cache output trees that an agent
   should almost never edit directly."
  #{".git" "target" "node_modules" ".svn" "CVS"})

(defn within-write-dir?
  "Return true if `path` is inside `write-dir`.

   A nil or empty `write-dir` means \"no containment enforced\" and the
   function returns true for any path. Otherwise the check uses
   `Path.startsWith`, so both paths should be normalized/absolute for a
   meaningful comparison."
  [^Path write-dir ^Path path]
  (if (or (nil? write-dir)
          (empty? (.toString write-dir)))
    true
    (boolean (.startsWith path write-dir))))

(defn blocked-path?
  "Return true if any segment of `path` appears in `blocked-paths`.

   `blocked-paths` defaults to [[default-blocked-paths]] when omitted.
   Segments are compared case-sensitively against the set."
  ([^Path path] (blocked-path? path default-blocked-paths))
  ([^Path path blocked-paths]
   (let [segs (iterator-seq (.iterator path))
         blocked (or blocked-paths default-blocked-paths)]
     (boolean (some #(contains? blocked (str %)) segs)))))

(defn clojure-file?
  "Return true if `path` has a Clojure/EDN extension.

   Recognized case-insensitive extensions: `.clj`, `.cljs`, `.cljc`,
   `.cljd`, `.edn`."
  [^Path path]
  (let [ext (some-> (str path) str/lower-case
                    (as-> s (re-find #"\.([^.]+)$" s) (second s)))]
    (contains? #{"clj" "cljs" "cljc" "cljd" "edn"} ext)))

(defn make-backup!
  "Write a timestamped sidecar backup of `path` and return its path as
   a string, or nil if `path` does not exist.

   The backup is written next to the original as
   `<path>.bak.<millis>` where `<millis>` is
   `(System/currentTimeMillis)`. Parent directories are created if
   needed. The original bytes are copied verbatim so binary and text
   files round-trip identically."
  [^Path path]
  (let [f (.toFile path)]
    (when (.exists f)
      (let [bak (File. (str (.toString path) ".bak." (System/currentTimeMillis)))
            parent (.getParentFile bak)]
        (when (and parent (not (.exists parent)))
          (.mkdirs parent))
        (io/copy f bak)
        (.toString bak)))))

(defn restore!
  "Restore `path` from a backup file produced by [[make-backup!]].

   Slurps the backup and spits it back to `path`. Returns nil. Does
   nothing if the backup does not exist."
  [^Path path backup-path-str]
  (let [bak (io/file backup-path-str)]
    (when (.exists bak)
      (spit (.toFile path) (slurp bak) :encoding "UTF-8"))
    nil))

(defn sha256
  "Return the SHA-256 digest of `bytes` as a lowercase hex string."
  [^bytes bytes]
  (let [md (MessageDigest/getInstance "SHA-256")
        digest (.digest md bytes)]
    (->> digest
         (map #(format "%02x" (bit-and % 0xff)))
         (apply str))))

(defn staleness-sentinel
  "Build a staleness sentinel for `path` given its in-memory `bytes`.

   The sentinel captures the on-disk mtime, byte size, and SHA-256 of
   the content the tool believes it is editing. [[check-staleness]]
   compares the live file against this sentinel to detect concurrent
   modification."
  [^Path path ^bytes bytes]
  (let [f (.toFile path)]
    {:mtime  (.lastModified f)
     :size   (count bytes)
     :sha256 (sha256 bytes)}))

(defn check-staleness
  "Return true if the live file at `path` differs from `sentinel`.

   `sentinel` is a map produced by [[staleness-sentinel]] with
   `:mtime`, `:size`, and `:sha256`. Any mismatch in mtime, size, or
   digest counts as stale. A missing file is treated as stale."
  [^Path path sentinel]
  (let [f (.toFile path)]
    (if (not (.exists f))
      true
      (let [cur-mtime (.lastModified f)
            cur-bytes (try (Files/readAllBytes path)
                           (catch Throwable _ nil))]
        (or (nil? cur-bytes)
            (not= (:mtime sentinel) cur-mtime)
            (not= (:size sentinel) (count cur-bytes))
            (not= (:sha256 sentinel) (sha256 cur-bytes)))))))

(defn write-atomically!
  "Write `content` to `path` using an atomic move.

   A temp file named `<basename>.lateralus-write-<n>` is created in the
   SAME parent directory as `path`, the UTF-8 bytes are written to it,
   and it is then moved into place with
   `Files/move` + `ATOMIC_MOVE` + `REPLACE_EXISTING`. The temp file is
   deleted in a `finally` block if it still exists (e.g. after a failed
   move), so no stale sidecars are left behind."
  [^Path path ^String content]
  (let [parent (.getParent path)
        base (.getFileName path)
        n (str (java.util.UUID/randomUUID))
        temp (if parent
               (.resolve parent (str base ".lateralus-write-" n))
               (.resolve (str base ".lateralus-write-" n)))
        bytes (.getBytes content StandardCharsets/UTF_8)]
    (try
      (Files/write temp bytes (into-array [java.nio.file.StandardOpenOption/CREATE
                                           java.nio.file.StandardOpenOption/WRITE
                                           java.nio.file.StandardOpenOption/TRUNCATE_EXISTING]))
      (Files/move temp path
                  (into-array [StandardCopyOption/ATOMIC_MOVE
                               StandardCopyOption/REPLACE_EXISTING]))
      (finally
        (try
          (when (Files/exists temp (into-array []))
            (Files/delete temp))
          (catch Throwable _ nil))))))

(defn- strip-bom
  "Strip a single leading U+FEFF from `s` and return
   `[bom? stripped-s]`."
  [^String s]
  (if (and (pos? (.length s)) (= (.charAt s 0) (char 0xFEFF)))
    [true (.substring s 1)]
    [false s]))

(defn- trim-trailing-per-line
  "Trim trailing whitespace from each line of `s` (already LF-normalized)."
  [^String s]
  (->> (str/split s #"\n")
       (mapv str/trimr)
       (str/join "\n")))

(defn normalize-for-match
  "Normalize `s` for fuzzy matching and return a map describing the
   transformation.

   Returns `{:normalized s' :eol-style :crlf|:lf :bom? bool}`.

   Steps (pragmatic, not exhaustive):
     * strip a leading U+FEFF BOM
     * detect EOL style (`:crlf` if the string contains `\\r\\n`,
       else `:lf`)
     * NFKC normalize via `java.text.Normalizer`
     * replace smart quotes U+2018 U+2019 U+201B -> `'`,
       U+201C U+201D U+201E -> `\"`
     * replace en/em dash U+2013 U+2014 U+2015 -> `-`
     * replace NBSP U+00A0 and narrow NBSP U+202F -> space
     * convert CRLF and lone CR to LF in the normalized string
     * trim trailing whitespace per line"
  [^String s]
  (let [[bom? s1] (strip-bom s)
        eol-style (if (str/includes? s1 "\r\n") :crlf :lf)
        nfkc (Normalizer/normalize s1 Normalizer$Form/NFKC)
        q1 (-> nfkc
               (str/replace (str (char 0x2018)) "'")
               (str/replace (str (char 0x2019)) "'")
               (str/replace (str (char 0x201B)) "'")
               (str/replace (str (char 0x201C)) "\"")
               (str/replace (str (char 0x201D)) "\"")
               (str/replace (str (char 0x201E)) "\""))
        q2 (-> q1
               (str/replace (str (char 0x2013)) "-")
               (str/replace (str (char 0x2014)) "-")
               (str/replace (str (char 0x2015)) "-"))
        q3 (-> q2
               (str/replace (str (char 0x00A0)) " ")
               (str/replace (str (char 0x202F)) " "))
        lf (-> q3
               (str/replace "\r\n" "\n")
               (str/replace "\r" "\n"))
        trimmed (trim-trailing-per-line lf)]
    {:normalized trimmed
     :eol-style eol-style
     :bom? (boolean bom?)}))

(defn- all-index-of
  "Collect every `[start end]` for non-overlapping occurrences of
   `needle` in `haystack` using `String.indexOf`. Case-sensitive."
  [^String haystack ^String needle]
  (let [nlen (.length needle)]
    (if (or (zero? (.length haystack)) (zero? nlen))
      []
      (loop [start 0 acc []]
        (let [idx (.indexOf haystack needle start)]
          (if (neg? idx)
            acc
            (recur (+ idx nlen) (conj acc [idx (+ idx nlen)]))))))))

(defn- whitespace-tolerant-offsets
  "Find offsets of `needle` in `haystack-original` ignoring differences
   in whitespace runs. Returns a vector of `[start end]` pairs in the
   ORIGINAL string. Used as the pragmatic fuzzy->original mapping."
  [^String haystack-original ^String needle]
  (let [parts (->> (str/split needle #"\s+")
                   (filter seq))
        n (count parts)]
    (if (zero? n)
      []
      (let [pattern (re-pattern (str "(?s)" (str/join "\\s+" (map #(java.util.regex.Pattern/quote %) parts))))
            matcher (.matcher pattern haystack-original)]
        (loop [acc [] found (.find matcher)]
          (if (not found)
            acc
            (recur (conj acc [(.start matcher) (.end matcher)]) (.find matcher))))))))

(defn- fuzzy-window-scan
  "Scan `haystack-original` for fuzzy matches of `needle`.

  Slides a window of (count needle) characters across the original
  haystack, normalizes each window via [[normalize-for-match]], and
  compares it to the normalized needle. Returns a vector of
  `[start end]` pairs in ORIGINAL-string offsets. This is what
  actually resolves 1:1 Unicode normalizations (smart quotes, en/em
  dash, NBSP, leading BOM) that [[whitespace-tolerant-offsets]]
  misses, because it never re-scans with the original needle. Does
  NOT handle normalizations that change length (e.g. NFKC ligature
  expansion such as U+FB01 -> \"fi\")."
  [^String haystack-original ^String needle]
  (let [norm-needle (:normalized (normalize-for-match needle))
        nlen        (.length needle)
        hlen        (.length haystack-original)]
    (if (or (zero? nlen) (zero? hlen) (< hlen nlen))
      []
      (loop [i 0 acc []]
        (if (> i (- hlen nlen))
          acc
          (let [window      (.substring haystack-original i (+ i nlen))
                norm-window (:normalized (normalize-for-match window))]
            (if (= norm-window norm-needle)
              (recur (inc i) (conj acc [i (+ i nlen)]))
              (recur (inc i) acc))))))))

(defn- dedup-by-start
  "De-duplicate a seq of `[start end]` pairs by `start`, keeping the
  first occurrence in start order. Used by [[find-matches]] to merge
  the window-scan and whitespace-tolerant hit sets."
  [pairs]
  (->> pairs
       (sort-by first)
       (reduce (fn [acc [s _ :as p]]
                 (if (and (seq acc) (= (first (peek acc)) s))
                   acc
                   (conj acc p)))
               [])))

(defn find-matches
  "Find occurrences of `needle` in `haystack`.

   Returns `{:matches [{:start :end :fuzzy?}] :fuzzy-fired? bool}`.

   First tries exact, case-sensitive matching via `String.indexOf` and
   collects ALL non-overlapping `[start end)` pairs. If at least one
   exact match exists, `:fuzzy-fired?` is false and the matches are
   returned as-is.

   If there are zero exact matches AND `fuzzy?` is true, both strings
   are run through [[normalize-for-match]] and occurrences are searched
   in normalized space. Each normalized hit is mapped back to ORIGINAL
   offsets by unioning two complementary scans and de-duplicating by
   start index: [[fuzzy-window-scan]] (resolves 1:1 Unicode deltas
   such as smart quotes, dashes, NBSP, leading BOM) and
   [[whitespace-tolerant-offsets]] (resolves EOL/whitespace-run
   differences that change length). If neither yields hits, `:matches`
   is returned empty with `:fuzzy-fired?` true.

   `:fuzzy-fired?` is set true iff the fuzzy path was actually used and
   produced matches."
  [^String haystack ^String needle fuzzy?]
  (let [exact (all-index-of haystack needle)]
    (if (seq exact)
      {:matches (mapv (fn [[s e]] {:start s :end e :fuzzy? false}) exact)
       :fuzzy-fired? false}
      (if (not fuzzy?)
        {:matches [] :fuzzy-fired? false}
        (let [nh (:normalized (normalize-for-match haystack))
              nn (:normalized (normalize-for-match needle))
              norm-hits (all-index-of nh nn)]
          (if (empty? norm-hits)
            {:matches [] :fuzzy-fired? false}
            (let [win-hits (fuzzy-window-scan haystack needle)
                  ws-hits  (whitespace-tolerant-offsets haystack needle)
                  hits     (dedup-by-start (concat win-hits ws-hits))]
              (if (seq hits)
                {:matches (mapv (fn [[s e]] {:start s :end e :fuzzy? true}) hits)
                 :fuzzy-fired? true}
                {:matches [] :fuzzy-fired? true}))))))))

(defn scan-omission-placeholders
  "Return the first omission-placeholder match found in `content`, or
   nil.

   Detects the common agent shorthand markers that indicate a tool was
   given elided code rather than the real source: `// ... existing
   code ...`, `... existing code ...`, `<...>`, `<TODO>`,
   `<placeholder>`, and `# ... existing ...`."
  [^String content]
  (let [re #"(?s)//\s*\.\.\.\s*existing\s+code\s*\.\.\.|\.\.\.\s*existing\s+code\s*\.\.\.|<\s*\.\.\.\s*>|<TODO>|<placeholder>|#\s*\.\.\.\s*existing\s*\.\.\."]
    (re-find re content)))

(defn scan-line-number-prefixes
  "Return the first line-number-prefix match found in `old-text`, or
   nil.

   Matches lines beginning with optional whitespace, digits, then a
   separator of whitespace, `|`, or `:` — the typical shape of code
   pasted from a numbered listing rather than the real source."
  [^String old-text]
  (let [re #"(?m)^\s*\d+[\s|:]"]
    (re-find re old-text)))

(defn clojure-round-trip?
  "Lazily parse `content` as Clojure via rewrite-clj and report whether
   it round-trips.

   Returns `{:ok true}` on success, or `{:ok false :reason msg}` when
   rewrite-clj cannot be loaded or the content fails to parse.
   `rewrite-clj` is required INSIDE a `try` so this namespace stays
   loadable on classpaths that do not include rewrite-clj."
  [^String content]
  (try
    (require '[rewrite-clj.zip :as z])
    (let [z-ns (the-ns 'rewrite-clj.zip)]
      (if-let [of-string (ns-resolve z-ns 'of-string*)]
        (do
          (of-string content)
          {:ok true})
        {:ok false :reason "rewrite-clj.zip/of-string* not resolvable"}))
    (catch Throwable t
      {:ok false :reason (ex-message t)})))

(def ^:private path-locks
  "Map of path-string -> lock `Object`, used by [[lock-for]] and
   [[with-path-lock]] to serialize edits to the same file across
   threads."
  (atom {}))

(defn- lock-for
  "Return a stable per-path lock `Object`, creating one on first use.
   Mutates `path-locks` under a lock on the atom itself so two threads
   never get two different locks for the same path."
  [path-str]
  (locking path-locks
    (or (get @path-locks path-str)
        (let [l (Object.)]
          (swap! path-locks assoc path-str l)
          l))))

(defmacro with-path-lock
  "Execute `body` while holding the per-path lock for `path-str`.

   Expands to `(locking <lock-fn> path-str) (do body...))` where the
   per-path lock is fetched via the private [[lock-for]] helper. The
   `lock-for` function value is captured at macroexpansion time (via
   its private var) and embedded directly in the expansion, so the
   macro works from ANY calling namespace — not just this one —
   without needing `lock-for` to be public or referred. Built with
   `list` forms (no syntax-quote/backtick) per the project rule."
  [path-str & body]
  (list 'locking (list @#'lock-for path-str) (cons 'do body)))

(comment
  (require '[clojure.pprint :as pp])

  ;; Basic path checks against an absolute temp dir.
  (let [wd (.toPath (io/file "/tmp/lateralus-fs-demo"))]
    (println "within?" (within-write-dir? wd (.resolve wd "src/foo.clj")))
    (println "blocked?" (blocked-path? (.toPath (io/file "/tmp/.git/config")))))

  ;; Atomic write + staleness sentinel round-trip.
  (let [f (.toPath (io/file (System/getProperty "java.io.tmpdir") "lateralus-atom.txt"))
        _ (write-atomically! f "hello\n")
        bytes (Files/readAllBytes f)
        sent (staleness-sentinel f bytes)
        _ (Thread/sleep 5)
        _ (write-atomically! f "hello\n")           ; same content, new mtime
        stale (check-staleness f sent)]
    (println "stale after rewrite?" stale))

  ;; Fuzzy match: smart quotes + NBSP should still match the plain
  ;; ASCII needle when fuzzy? is true.
  (let [hay (str "foo " (char 0x2018) "bar" (char 0x2019) " baz")
        res (find-matches hay "'bar'" true)]
    (pp/pprint res)))