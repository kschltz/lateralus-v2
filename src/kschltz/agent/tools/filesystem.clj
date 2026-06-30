(ns kschltz.agent.tools.filesystem
  "Filesystem Tool implementations for the lateralus tool-calling loop.

   These are file operations exposed to the LLM: read a file, list a
   directory, get file metadata, search for text inside a directory
   tree, create a file, overwrite a file, and apply in-place edits to a
   file. Paths may be absolute or relative; when a `:workspace-root` is
   provided, relative paths are resolved against it.

   `file/read`, `file/list`, `file/info`, `file/search`, and
   `file/create` are thin convenience wrappers that live in this
   namespace. `file/create` is a create-only convenience that
   silently overwrites and creates parent directories; it does NOT
   enforce containment, block paths, or back up the previous file.

   `file/write` and `file/update` are the safe mutation tools. Their
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
            [kschltz.agent.tools.file-path :as fpath]
            [kschltz.agent.tools.file-write :as fw])
  (:import [java.io BufferedReader File InputStream InputStreamReader]
           [java.nio.charset CharsetDecoder CodingErrorAction StandardCharsets]
           [java.nio.file Files Path]))

(def default-max-read-bytes
  "Default hard ceiling on the byte length of `file/read`'s line-numbered
   `:content` string. Not a gate that refuses a file — reads beyond the
   budget return a window with a continuation marker."
  (* 256 1024))

(def default-max-search-file-bytes
  "Default upper bound on how many bytes `file/search` will read from a
   single file while scanning."
  (* 128 1024))

(def default-max-search-results
  "Default cap on the number of text search hits returned."
  100)

(def InputSchema:Path
  "Common input schema for tools that take a single path."
  [:map
   [:path :string]])

(def InputSchema:ReadFile
  "Input schema for `file/read`."
  [:map
   [:path :string]
   [:offset {:optional true} :int]
   [:limit {:optional true} :int]])

(def InputSchema:SearchFiles
  "Input schema for `file/search`."
  [:map
   [:path :string]
   [:pattern :string]
   [:max-results {:optional true} :int]])

(def InputSchema:CreateFile
  "Input schema for `file/create`."
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
    (format "\n\n[file-window: %s lines %d-%d of %d; call file/read again with offset=%d to continue]"
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
        path-str (fpath/path->str path)]
    (with-open [is (io/input-stream (.toFile path))
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
  "Build the JSON string result for a `file/read` invocation.

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
  [^Path path]
  (let [dir (.toFile path)]
    (if (.isDirectory dir)
      (mapv describe-entry (.listFiles dir))
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
                      {:pattern pattern})))))

(defn- do-search-files
  "Recursively search files under `path` for `pattern`. Returns up to
   `max-results` hits as EDN maps with `:file`, `:line`, and `:text`.
   Skips files larger than `max-search-file-bytes`. Uses
   `default-max-results` when the caller does not supply a per-call cap."
  [^Path path pattern max-results max-search-file-bytes default-max-results]
  (let [re (compile-pattern pattern)
        max (safe-int max-results default-max-results)
        results (volatile! [])]
    (doseq [^File f (file-seq (.toFile path))
            :when (and (.isFile f)
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
  "Format an exception as a model-readable string."
  [t]
  (format "Filesystem tool error: %s" (ex-message t)))

(deftype ReadFileTool [workspace-root max-read-bytes]
  tool/Tool
  (-name [_] "file/read")
  (-description [_]
    "Read the contents of a UTF-8 text file with line numbers. `offset` is the 1-based line to start at (default 1); `limit` is the max number of lines returned (default 2000). Lines are prefixed with their absolute line number. Large files are returned as a window with a continuation marker rather than erroring. Binary files are reported, not thrown.")
  (-input-schema [_] InputSchema:ReadFile)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args _ctx]
    (try
      (read-file-json (fpath/resolve-path workspace-root (:path args))
                      (:offset args)
                      (:limit args)
                      max-read-bytes)
      (catch Throwable t
        (error-result t)))))

(deftype ListDirectoryTool [workspace-root]
  tool/Tool
  (-name [_] "file/list")
  (-description [_]
    "List the files and directories inside a directory. Returns a JSON object with an `entries` array; each entry has `name` and `type` (`file`, `directory`, or `other`).")
  (-input-schema [_] InputSchema:Path)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args _ctx]
    (try
      (json/generate-string
       {:entries (do-list-directory (fpath/resolve-path workspace-root (:path args)))})
      (catch Throwable t
        (error-result t)))))

(deftype FileInfoTool [workspace-root]
  tool/Tool
  (-name [_] "file/info")
  (-description [_]
    "Return metadata for a path: whether it exists, its type (`file`, `directory`, or `other`), size in bytes, and last modified timestamp.")
  (-input-schema [_] InputSchema:Path)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args _ctx]
    (try
      (json/generate-string
       (do-file-info (fpath/resolve-path workspace-root (:path args))))
      (catch Throwable t
        (error-result t)))))

(deftype SearchFilesTool [workspace-root max-search-file-bytes default-max-search-results]
  tool/Tool
  (-name [_] "file/search")
  (-description [_]
    "Recursively search files under a directory for a regex pattern. Returns up to `max-results` matches as JSON objects with `file`, `line`, and `text`. Files larger than the registry's `:max-search-file-bytes` setting are skipped.")
  (-input-schema [_] InputSchema:SearchFiles)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args _ctx]
    (try
      (json/generate-string
       (do-search-files (fpath/resolve-path workspace-root (:path args))
                        (:pattern args)
                        (:max-results args)
                        max-search-file-bytes
                        default-max-search-results))
      (catch Throwable t
        (error-result t)))))

(defn- do-create-file [^Path path content]
  (let [file (.toFile path)]
    (.mkdirs (.getParentFile file))
    (spit file (or content "") :encoding "UTF-8")
    {:path (fpath/path->str path)
     :created true
     :size (.length file)}))

(deftype CreateFileTool [workspace-root]
  tool/Tool
  (-name [_] "file/create")
  (-description [_]
    "Create a new UTF-8 text file (and any missing parent directories) with the given content. Paths are resolved against the configured workspace root.")
  (-input-schema [_] InputSchema:CreateFile)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args _ctx]
    (try
      (json/generate-string
       (do-create-file (fpath/resolve-path workspace-root (:path args)) (:content args)))
      (catch Throwable t
        (error-result t)))))

(defn read-file
  "Return a new `file/read` Tool instance."
  ([] (read-file nil default-max-read-bytes))
  ([workspace-root] (read-file workspace-root default-max-read-bytes))
  ([workspace-root max-read-bytes]
   (->ReadFileTool workspace-root max-read-bytes)))

(defn list-directory
  "Return a new `file/list` Tool instance."
  ([] (list-directory nil))
  ([workspace-root]
   (->ListDirectoryTool workspace-root)))

(defn file-info
  "Return a new `file/info` Tool instance."
  ([] (file-info nil))
  ([workspace-root]
   (->FileInfoTool workspace-root)))

(defn search-files
  "Return a new `file/search` Tool instance."
  ([] (search-files nil default-max-search-file-bytes default-max-search-results))
  ([workspace-root] (search-files workspace-root default-max-search-file-bytes default-max-search-results))
  ([workspace-root max-search-file-bytes default-max-search-results]
   (->SearchFilesTool workspace-root max-search-file-bytes default-max-search-results)))

(defn create-file
  "Return a new `file/create` Tool instance."
  ([] (create-file nil))
  ([workspace-root]
   (->CreateFileTool workspace-root)))

(defn write-file
  "Return a new `file/write` Tool instance. Re-exported from
   [[kschltz.agent.tools.file-write/write-file]] so callers can
   continue to use `kschltz.agent.tools.filesystem/write-file`."
  ([] (fw/write-file))
  ([workspace-root] (fw/write-file workspace-root))
  ([workspace-root opts] (fw/write-file workspace-root opts)))

(defn update-file
  "Return a new `file/update` Tool instance. Re-exported from
   [[kschltz.agent.tools.file-write/update-file]] so callers can
   continue to use `kschltz.agent.tools.filesystem/update-file`."
  ([] (fw/update-file))
  ([workspace-root] (fw/update-file workspace-root))
  ([workspace-root opts] (fw/update-file workspace-root opts)))

(defn filesystem-registry
  "Return a map of filesystem tool name -> Tool instance.

   Accepts an optional `opts` map with:
     :workspace-root          — root for resolving relative paths
     :max-read-bytes          — cap for `file/read` (default 256 KB)
     :max-search-file-bytes   — per-file cap for `file/search`
                                   (default 128 KB)
     :max-search-results      — default hit cap for `file/search`
                                   (default 100)
     :max-write-bytes         — cap for `file/write` and `file/update`
                                   (default 10 MiB)
     :refuse-clojure?         — refuse Clojure/EDN targets unless a
                                   call sends `:clj-override`
                                   (default true)
     :blocked-paths           — set of forbidden path segments
                                   (default .git, target, node_modules, .svn, CVS)
     :clojure-guard?          — round-trip-validate Clojure/EDN results
                                   via rewrite-clj (default false)

   When `:workspace-root` is omitted, the current working directory is
   used. `file/write` and `file/update` enforce workspace-root
   containment (per-call `:force` skips it) and always refuse blocked
   path segments; `file/create` remains a thin create-only wrapper
   that does not enforce containment."
  ([] (filesystem-registry {}))
  ([{:keys [workspace-root
            max-read-bytes
            max-search-file-bytes
            max-search-results
            max-write-bytes
            refuse-clojure?
            blocked-paths
            clojure-guard?]}]
   (let [write-opts {:max-write-bytes  max-write-bytes
                     :refuse-clojure? refuse-clojure?
                     :blocked-paths   blocked-paths
                     :clojure-guard?  clojure-guard?}]
     {"file/read"    (read-file workspace-root (or max-read-bytes default-max-read-bytes))
      "file/list"    (list-directory workspace-root)
      "file/info"    (file-info workspace-root)
      "file/create"  (create-file workspace-root)
      "file/search"  (search-files workspace-root
                                  (or max-search-file-bytes default-max-search-file-bytes)
                                  (or max-search-results default-max-search-results))
      "file/write"   (fw/write-file workspace-root write-opts)
      "file/update"  (fw/update-file workspace-root write-opts)})))
