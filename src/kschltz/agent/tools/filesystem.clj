(ns kschltz.agent.tools.filesystem
  "Filesystem Tool implementations for the lateralus tool-calling loop.

   These are file operations exposed to the LLM: read a file, list a
   directory, get file metadata, search for text inside a directory
   tree, create a file, overwrite a file, and apply in-place edits to a
   file. Paths may be absolute or relative, but all operations are constrained
   to the canonical `:workspace-root` by default. Operators may explicitly
   allow outside-workspace reads; writes remain independently guarded.

   `file_read`, `file_list`, `file_info`, `file_search`, and
   `file_create` are convenience wrappers that live in this namespace.
   `file_create` delegates to the safe writer in strict create-only mode,
   so it creates parents but never overwrites and retains all containment,
   blocked-path, size, omission, atomic-write, and locking guarantees.

   `file_write` and `file_update` are the safe mutation tools. Their
   deftypes, schemas, edit-validation helpers, and factory functions
   live in `kschltz.agent.tools.file-write` so this namespace can
   stay under the project's per-file line budget. The behavior is
   unchanged: they enforce `:workspace-root` containment (skippable
   per-call via `:force`), refuse to touch blocked path segments
   (`.git`, `target`, `node_modules`, ... — never skippable), can
   refuse Clojure/EDN source files unless `:clj-override` is set,
   write a timestamped `.bak.<millis>` sidecar backup of the original
   before mutating, and land the new content with an atomic temp-file
   + move so a crashed write never leaves a truncated file. Edits to
   the same path are serialized across threads via a per-path lock.

   All hard limits are configurable via the registry options:
   `:max-read-bytes`, `:max-search-file-bytes`, `:max-search-results`,
   `:max-write-bytes`, `:refuse-clojure?`, `:blocked-paths`, and
   `:clojure-guard?`. Sensible defaults are used when a limit is
   omitted."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.file-glob :as file-glob]
            [kschltz.agent.tools.file-patch :as file-patch]
            [kschltz.agent.tools.file-path :as fpath]
            [kschltz.agent.tools.file-safety :as fs]
            [kschltz.agent.tools.file-write :as fw])
  (:import [java.io BufferedReader File InputStream InputStreamReader]
           [java.nio.charset CharsetDecoder CodingErrorAction StandardCharsets]
           [java.nio.file Files Path]
           [java.security DigestInputStream MessageDigest]))

(def default-max-read-bytes
  "Default hard ceiling on the byte length of `file_read`'s line-numbered
   `:content` string. Not a gate that refuses a file — reads beyond the
   budget return a window with a continuation marker."
  (* 256 1024))

(def default-max-search-file-bytes
  "Default upper bound on how many bytes `file_search` will read from a
   single file while scanning."
  (* 128 1024))

(def default-max-search-results
  "Default cap on the number of text search hits returned."
  100)

(def default-max-list-entries
  "Default cap for one `file_list` response."
  500)

(def InputSchema:Path
  "Common input schema for tools that take a single path."
  [:map
   [:path :string]])

(def InputSchema:ListDirectory
  [:map
   [:path :string]
   [:max-entries {:optional true} [:int {:min 1}]]])

(def InputSchema:ReadFile
  "Input schema for `file_read`."
  [:map
   [:path :string]
   [:offset {:optional true} :int]
   [:limit {:optional true} :int]])

(def InputSchema:SearchFiles
  "Input schema for `file_search`."
  [:map
   [:path :string]
   [:pattern :string]
   [:max-results {:optional true} :int]])

(def InputSchema:CreateFile
  "Input schema for `file_create`."
  [:map
   [:path :string]
   [:content {:optional true} [:maybe :string]]])

(def OutputSchema:String
  "All filesystem tools return a JSON or plain string."
  :string)

(defn- safe-int
  "Coerce a value to a positive integer, returning default if missing or invalid."
  [value default]
  (let [v (if (integer? value)
            value
            (try (Long/parseLong (str value))
                 (catch Throwable _ default)))]
    (if (pos? v) v default)))

(defn- byte-count
  "UTF-8 byte length of `s`."
  [^String s]
  (alength (.getBytes s "UTF-8")))

(defn- resolve-readable-path
  "Resolve a model-provided path under the configured read policy."
  [workspace-root user-path blocked-paths allow-read-outside-workspace?]
  (let [requested (fpath/resolve-path workspace-root user-path)
        canonical (fs/canonical-path requested)
        root (fs/canonical-path
              (.toPath (fpath/workspace-root->file workspace-root)))]
    (cond
      (and (not allow-read-outside-workspace?)
           (not (fs/within-write-dir? root canonical)))
      (throw (ex-info "Path resolves outside the configured workspace"
                      {:error :outside-workspace
                       :path (fpath/path->str requested)}))

      (or (fs/blocked-path? requested blocked-paths)
          (fs/blocked-path? canonical blocked-paths))
      (throw (ex-info "Path contains a blocked segment"
                      {:error :blocked-path
                       :path (fpath/path->str requested)}))

      :else canonical)))

(defn- read-first-bytes
  "Read up to `n` bytes from `path`, returning a byte array of the bytes
  actually read (length 0 at EOF). Does not load the whole file."
  [^Path path ^long n]
  (with-open [is (io/input-stream (.toFile path))]
    (let [buf (byte-array n)
          read (.read is buf 0 n)]
      (if (neg? read)
        (byte-array 0)
        (java.util.Arrays/copyOf buf read)))))

(defn- binary-sample?
  "Return true if `sample` (a byte array of length `sample-len`) looks
  binary: it contains a NUL byte, or the control-byte ratio exceeds
  0.30. Control bytes are bytes < 0x20 (except the allowed whitespace
  set \\t \\n \\r \\f \\v) and 0x7F. Bytes >= 0x80 are not control bytes
  (valid UTF-8 lead/continuation bytes)."
  [^bytes sample ^long sample-len]
  (cond
    (zero? sample-len) false
    (loop [i 0]
      (cond
        (>= i sample-len) false
        (zero? (bit-and (aget sample i) 0xFF)) true
        :else (recur (inc i))))
    true
    :else
    (let [control (loop [i 0 c 0]
                     (if (>= i sample-len)
                       c
                       (let [b (bit-and (aget sample i) 0xFF)]
                         (cond
                           (or (== b 0x09) (== b 0x0A) (== b 0x0D)
                               (== b 0x0C) (== b 0x0B))
                           (recur (inc i) c)
                           (or (< b 0x20) (== b 0x7F))
                           (recur (inc i) (inc c))
                           :else (recur (inc i) c)))))]
      (> (/ (double control) (double sample-len)) 0.30))))

(defn- fit-truncated-line
  "Return the formatted content string for a single in-window line that
  alone exceeds `max-read-bytes`: the line-numbered prefix plus as much
  of `line` as fits, then the literal ` (line truncated to <N> chars)`
  suffix. `<N>` is the number of characters of `line` kept. The whole
  string fits within `max-read-bytes` bytes."
  [^long line-no ^String line ^long max-read-bytes]
  (let [prefix (format "%6d\t" line-no)
        prefix-bytes (byte-count prefix)
        kept (loop [lo 0 hi (count line)]
               (if (>= lo hi)
                 lo
                 (let [mid (quot (+ lo hi 1) 2)
                       mid-suffix (str " (line truncated to " mid " chars)")
                       total (+ prefix-bytes
                                (byte-count (subs line 0 mid))
                                (byte-count mid-suffix))]
                   (if (<= total max-read-bytes)
                     (recur mid hi)
                     (recur lo (dec mid))))))
        suffix (str " (line truncated to " kept " chars)")]
    (str prefix (subs line 0 kept) suffix)))

(defn- continuation-marker
  "Build the `:content`-appended continuation marker for a truncated read."
  [path-str offset lines-returned total-lines]
  (let [end (+ offset lines-returned -1)
        next (inc end)]
   (format "\n\n[file-window: %s lines %d-%d of %d; call file_read again with offset=%d to continue]"
            path-str offset end total-lines next)))

(defn- read-lines-with-window
  "Stream-read `path` line by line as UTF-8, building the line-numbered
  content window and the total line count in a single linear scan.

  `offset` is the 1-based line to start at (already coerced/clamped to
  >= 1); `limit` is the max lines to place into `:content`; `max-read-bytes`
  is the hard byte ceiling on the formatted `:content` string. Returns the
  result map with `:path :size :total-lines :offset :limit
  :lines-returned :truncated :content`. Throws a `CharacterCodingException`
  if the file is not valid UTF-8 (caught by the caller)."
  [^Path path size offset limit max-read-bytes]
  (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                  (.onMalformedInput CodingErrorAction/REPORT)
                  (.onUnmappableCharacter CodingErrorAction/REPORT))
        path-str (fpath/path->str path)
        digest (MessageDigest/getInstance "SHA-256")]
    (with-open [raw (io/input-stream (.toFile path))
                is (DigestInputStream. raw digest)
                r (BufferedReader. (InputStreamReader. ^InputStream is ^CharsetDecoder decoder))]
      (loop [line-no 0
             collected 0
             buf (StringBuilder.)
             buf-bytes 0
             collecting true
             cut false]
        (let [line (.readLine ^BufferedReader r)]
          (cond
            ;; EOF: finalize.
            (nil? line)
            (let [total-lines line-no
                  lines-returned collected
                  truncated (or cut
                               (and (pos? lines-returned)
                                    (< (+ offset lines-returned -1) total-lines)))
                  content (.toString buf)
                  content-final (if truncated
                                  (str content (continuation-marker path-str offset lines-returned total-lines))
                                  content)]
              {:path path-str
               :size size
               :sha256 (->> (.digest digest)
                            (map #(format "%02x" (bit-and % 0xff)))
                            (apply str))
               :total-lines total-lines
               :offset offset
               :limit limit
               :lines-returned lines-returned
               :truncated truncated
               :content content-final})

            ;; Window closed (limit reached or budget cut): keep counting.
            (not collecting)
            (recur (inc line-no) collected buf buf-bytes collecting cut)

            ;; Not yet in the window: skip but keep counting.
            (< (inc line-no) offset)
            (recur (inc line-no) collected buf buf-bytes collecting cut)

            ;; In window and under the line limit: consider appending.
            :else
            (let [next-line-no (inc line-no)]
              (if (>= collected limit)
                ;; Line limit reached: stop collecting, keep counting.
                (recur next-line-no collected buf buf-bytes false cut)
                (let [fmt (format "%6d\t%s" next-line-no line)
                      fmt-bytes (byte-count fmt)
                      sep-bytes (if (pos? collected) 1 0)
                      cand-bytes (+ sep-bytes fmt-bytes)]
                  (if (<= (+ buf-bytes cand-bytes) max-read-bytes)
                    ;; Fits: append.
                    (recur next-line-no
                           (inc collected)
                           (do (when (pos? collected) (.append buf "\n"))
                               (.append buf fmt)
                               buf)
                           (+ buf-bytes cand-bytes)
                           collecting
                           cut)
                    ;; Would exceed the byte budget.
                    (if (pos? collected)
                      ;; Already have lines: stop, mark cut.
                      (recur next-line-no collected buf buf-bytes false true)
                      ;; First line of the window alone exceeds budget:
                      ;; return it truncated-to-budget.
                      (let [trunc (fit-truncated-line next-line-no line max-read-bytes)
                            trunc-bytes (byte-count trunc)]
                        (recur next-line-no
                               1
                               (do (.append buf trunc) buf)
                               trunc-bytes
                               false
                               true)))))))))))))

(defn- binary-file-result
  "Build the structured binary-file error map returned (as JSON) when a
  file is detected as binary or fails UTF-8 decoding."
  [^Path path ^long size]
  {:error "binary-file"
   :path (fpath/path->str path)
   :size size})

(defn- read-file-json
  "Build the JSON string result for a `file_read` invocation.

  Returns either the structured content map or the `{:error
  \"binary-file\" ...}` map, both as a JSON string. Unexpected I/O errors
  (e.g. permission denied) propagate to the caller, which maps them to
  `error-result`."
  [^Path path offset-arg limit-arg max-read-bytes]
  (let [size (Files/size path)
        sample-len (min 8192 size)
        sample (read-first-bytes path sample-len)]
    (if (binary-sample? sample sample-len)
      (json/generate-string (binary-file-result path size))
      (try
        (let [offset (safe-int offset-arg 1)
              limit (safe-int limit-arg 2000)
              result (read-lines-with-window path size offset limit max-read-bytes)]
          (json/generate-string result))
        (catch java.nio.charset.CharacterCodingException _
          (json/generate-string (binary-file-result path size)))))))

(defn- describe-entry
  "Return an EDN map describing a directory entry."
  [^File f]
  {:name (.getName f)
   :type (cond
           (.isDirectory f) "directory"
           (.isFile f)      "file"
           :else            "other")})

(defn- do-list-directory
  "List children of `path`. Returns a vector of EDN maps."
  [^Path path max-entries]
  (let [dir (.toFile path)]
    (if (.isDirectory dir)
      (let [entries (->> (.listFiles dir)
                         (map describe-entry)
                         (sort-by :name)
                         vec)
            total (count entries)
            selected (subvec entries 0 (min total max-entries))]
        {:entries selected
         :total-entries total
         :truncated (< (count selected) total)})
      (throw (ex-info "Path is not a directory" {:path (fpath/path->str path)})))))

(defn- do-file-info
  "Return metadata for `path` as an EDN map."
  [^Path path]
  (let [f (.toFile path)]
    {:path          (fpath/path->str path)
     :exists        (.exists f)
     :type          (cond
                      (.isDirectory f) "directory"
                      (.isFile f)      "file"
                      :else              "other")
     :size          (when (.isFile f) (.length f))
     :last-modified (when (.exists f) (.lastModified f))}))

(defn- compile-pattern
  "Compile `pattern` as a case-insensitive regex. Returns the pattern or
   throws a clear exception."
  [pattern]
  (try
    (re-pattern (str "(?i)" pattern))
    (catch Throwable t
      (throw (ex-info (str "Invalid search pattern: " (ex-message t))
                      {:error :invalid-pattern
                       :pattern pattern})))))

(defn- do-search-files
  "Recursively search files under `path` for `pattern`. Returns up to
   `max-results` hits as EDN maps with `:file`, `:line`, and `:text`.
   Skips files larger than `max-search-file-bytes`. Uses
   `default-max-results` when the caller does not supply a per-call cap."
  [^Path path pattern max-results max-search-file-bytes default-max-results blocked-paths]
  (let [re (compile-pattern pattern)
        max (safe-int max-results default-max-results)
        results (volatile! [])]
    (doseq [^File f (file-seq (.toFile path))
            :when (and (.isFile f)
                       (not (fs/blocked-path? (.toPath f) blocked-paths))
                       (<= (.length f) max-search-file-bytes))
            :while (< (count @results) max)]
      (try
        (let [lines (str/split-lines (slurp f :encoding "UTF-8"))]
          (doseq [[idx line] (map-indexed vector lines)
                  :when (re-find re line)
                  :while (< (count @results) max)]
            (vswap! results conj {:file (.getPath f)
                                  :line (inc idx)
                                  :text (str/trim line)})))
        (catch Throwable _
          ;; Skip files we cannot read as text.
          nil)))
    @results))

(defn- error-result
  "Format an exception as a structured model-readable JSON envelope."
  [t]
  (let [data (if (instance? clojure.lang.ExceptionInfo t)
               (ex-data t)
               {})]
    (json/generate-string
     (merge {:ok false
             :error (or (:error data) :filesystem-error)
             :message (or (ex-message t) (.getName (class t)))}
            (dissoc data :error)))))

(deftype ReadFileTool [workspace-root max-read-bytes blocked-paths allow-read-outside-workspace?]
  tool/Tool
  (-name [_] "file_read")
  (-description [_]
    "Read the contents of a UTF-8 text file with line numbers. `offset` is the 1-based line to start at (default 1); `limit` is the max number of lines returned (default 2000). Lines are prefixed with their absolute line number. Large files are returned as a window with a continuation marker rather than erroring. Binary files are reported, not thrown.")
  (-input-schema [_] InputSchema:ReadFile)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args _ctx]
    (try
      (read-file-json (resolve-readable-path workspace-root
                                             (:path args)
                                             blocked-paths
                                             allow-read-outside-workspace?)
                      (:offset args)
                      (:limit args)
                      max-read-bytes)
      (catch Throwable t
        (error-result t)))))

(deftype ListDirectoryTool [workspace-root default-max-entries blocked-paths allow-read-outside-workspace?]
  tool/Tool
  (-name [_] "file_list")
  (-description [_]
    "List the files and directories inside a directory. Returns a JSON object with an `entries` array; each entry has `name` and `type` (`file`, `directory`, or `other`).")
  (-input-schema [_] InputSchema:ListDirectory)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args _ctx]
    (try
      (json/generate-string
       (do-list-directory
        (resolve-readable-path workspace-root
                               (:path args)
                               blocked-paths
                               allow-read-outside-workspace?)
        (safe-int (:max-entries args) default-max-entries)))
      (catch Throwable t
        (error-result t)))))

(deftype FileInfoTool [workspace-root blocked-paths allow-read-outside-workspace?]
  tool/Tool
  (-name [_] "file_info")
  (-description [_]
    "Return metadata for a path: whether it exists, its type (`file`, `directory`, or `other`), size in bytes, and last modified timestamp.")
  (-input-schema [_] InputSchema:Path)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args _ctx]
    (try
      (json/generate-string
       (do-file-info
        (resolve-readable-path workspace-root
                               (:path args)
                               blocked-paths
                               allow-read-outside-workspace?)))
      (catch Throwable t
        (error-result t)))))

(deftype SearchFilesTool [workspace-root max-search-file-bytes default-max-search-results
                          blocked-paths allow-read-outside-workspace?]
  tool/Tool
  (-name [_] "file_search")
  (-description [_]
    "Recursively search files under a directory for a regex pattern. Returns up to `max-results` matches as JSON objects with `file`, `line`, and `text`. Files larger than the registry's `:max-search-file-bytes` setting are skipped.")
  (-input-schema [_] InputSchema:SearchFiles)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args _ctx]
    (try
      (json/generate-string
       (do-search-files (resolve-readable-path workspace-root
                                               (:path args)
                                               blocked-paths
                                               allow-read-outside-workspace?)
                        (:pattern args)
                        (:max-results args)
                        max-search-file-bytes
                        default-max-search-results
                        blocked-paths))
      (catch Throwable t
        (error-result t)))))

(deftype CreateFileTool [delegate]
  tool/Tool
  (-name [_] "file_create")
  (-description [_]
    "Safely create a new UTF-8 text file and missing parent directories. The operation is create-only: it refuses existing files. It enforces workspace containment, blocked-path, size, omission-placeholder, and optional Clojure guards, then lands content atomically.")
  (-input-schema [_] InputSchema:CreateFile)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args ctx]
    (tool/invoke-tool delegate
                      (assoc args :content (or (:content args) "")
                                  :create-dirs true
                                  :create-only true)
                      ctx)))

(defn read-file
  "Return a new `file_read` Tool instance."
  ([] (read-file nil default-max-read-bytes fs/default-blocked-paths false))
  ([workspace-root] (read-file workspace-root default-max-read-bytes fs/default-blocked-paths false))
  ([workspace-root max-read-bytes]
   (read-file workspace-root max-read-bytes fs/default-blocked-paths false))
  ([workspace-root max-read-bytes blocked-paths allow-read-outside-workspace?]
   (->ReadFileTool workspace-root max-read-bytes blocked-paths allow-read-outside-workspace?)))

(defn list-directory
  "Return a new `file_list` Tool instance."
  ([] (list-directory nil default-max-list-entries fs/default-blocked-paths false))
  ([workspace-root]
   (list-directory workspace-root default-max-list-entries fs/default-blocked-paths false))
  ([workspace-root max-entries blocked-paths allow-read-outside-workspace?]
   (->ListDirectoryTool workspace-root max-entries blocked-paths allow-read-outside-workspace?)))

(defn file-info
  "Return a new `file_info` Tool instance."
  ([] (file-info nil fs/default-blocked-paths false))
  ([workspace-root]
   (file-info workspace-root fs/default-blocked-paths false))
  ([workspace-root blocked-paths allow-read-outside-workspace?]
   (->FileInfoTool workspace-root blocked-paths allow-read-outside-workspace?)))

(defn search-files
  "Return a new `file_search` Tool instance."
  ([] (search-files nil default-max-search-file-bytes default-max-search-results
                    fs/default-blocked-paths false))
  ([workspace-root] (search-files workspace-root default-max-search-file-bytes
                                  default-max-search-results fs/default-blocked-paths false))
  ([workspace-root max-search-file-bytes default-max-search-results]
   (search-files workspace-root max-search-file-bytes default-max-search-results
                 fs/default-blocked-paths false))
  ([workspace-root max-search-file-bytes default-max-search-results
    blocked-paths allow-read-outside-workspace?]
   (->SearchFilesTool workspace-root max-search-file-bytes default-max-search-results
                      blocked-paths allow-read-outside-workspace?)))

(defn create-file
  "Return a new `file_create` Tool instance."
  ([] (create-file nil {}))
  ([workspace-root]
   (create-file workspace-root {}))
  ([workspace-root opts]
   (->CreateFileTool (fw/write-file workspace-root opts))))

(defn write-file
  "Return a new `file_write` Tool instance. Re-exported from
   [[kschltz.agent.tools.file-write/write-file]] so callers can
   continue to use `kschltz.agent.tools.filesystem/write-file`."
  ([] (fw/write-file))
  ([workspace-root] (fw/write-file workspace-root))
  ([workspace-root opts] (fw/write-file workspace-root opts)))

(defn update-file
  "Return a new `file_update` Tool instance. Re-exported from
   [[kschltz.agent.tools.file-write/update-file]] so callers can
   continue to use `kschltz.agent.tools.filesystem/update-file`."
  ([] (fw/update-file))
  ([workspace-root] (fw/update-file workspace-root))
  ([workspace-root opts] (fw/update-file workspace-root opts)))

(defn filesystem-registry
  "Return a map of filesystem tool name -> Tool instance.

   Accepts an optional `opts` map with:
     :workspace-root          — root for resolving relative paths
     :max-read-bytes          — cap for `file_read` (default 256 KB)
     :max-search-file-bytes   — per-file cap for `file_search`
                                   (default 128 KB)
     :max-search-results      — default hit cap for `file_search`
                                   (default 100)
     :max-list-entries       — cap for `file_list` (default 500)
     :max-glob-results       — cap for `file_glob` (default 500)
     :max-write-bytes         — cap for `file_write` and `file_update`
                                   (default 10 MiB)
     :refuse-clojure?         — refuse Clojure/EDN targets unless a
                                   call sends `:clj-override`
                                   (default true)
     :blocked-paths           — set of forbidden path segments
                                   (default .git, target, node_modules, .svn, CVS)
     :clojure-guard?          — round-trip-validate Clojure/EDN results
                                   via rewrite-clj (default false)
     :allow-read-outside-workspace?
                                — operator escape hatch for read/list/info/search
                                  (default false; blocked paths still apply)

   When `:workspace-root` is omitted, the current working directory is
   used. Reads enforce canonical containment unless explicitly configured
   otherwise. Writes enforce containment (`:force` skips only containment)
   and always refuse blocked path segments; `file_create` uses the same
   guardrails and never overwrites an existing path."
  ([] (filesystem-registry {}))
  ([{:keys [workspace-root
            max-read-bytes
            max-search-file-bytes
            max-search-results
            max-list-entries
            max-glob-results
            max-write-bytes
            refuse-clojure?
            blocked-paths
            clojure-guard?
            allow-read-outside-workspace?]}]
   (let [blocked-paths (or blocked-paths fs/default-blocked-paths)
         allow-read-outside-workspace? (boolean allow-read-outside-workspace?)
         write-opts {:max-write-bytes  max-write-bytes
                     :refuse-clojure? refuse-clojure?
                     :blocked-paths   blocked-paths
                     :clojure-guard?  clojure-guard?}]
     {"file_read"    (read-file workspace-root
                                (or max-read-bytes default-max-read-bytes)
                                blocked-paths
                                allow-read-outside-workspace?)
      "file_list"    (list-directory workspace-root
                                     (or max-list-entries default-max-list-entries)
                                     blocked-paths
                                     allow-read-outside-workspace?)
      "file_info"    (file-info workspace-root blocked-paths
                               allow-read-outside-workspace?)
      "file_glob"    (file-glob/file-glob
                      workspace-root
                      {:blocked-paths blocked-paths
                       :max-results max-glob-results})
      "file_patch"   (file-patch/file-patch workspace-root write-opts)
      "file_create"  (create-file workspace-root write-opts)
      "file_search"  (search-files workspace-root
                                  (or max-search-file-bytes default-max-search-file-bytes)
                                  (or max-search-results default-max-search-results)
                                  blocked-paths
                                  allow-read-outside-workspace?)
      "file_write"   (fw/write-file workspace-root write-opts)
      "file_update"  (fw/update-file workspace-root write-opts)})))
