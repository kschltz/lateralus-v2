(ns kschltz.agent.tools.filesystem
  "Read-only filesystem Tool implementations for the lateralus
   tool-calling loop.

   These are basic file operations exposed to the LLM: read a file,
   list a directory, get file metadata, and search for text inside a
   directory tree. Paths may be absolute or relative; when a
   `:workspace-root` is provided, relative paths are resolved against it.

   All hard limits are configurable via the registry options:
   `:max-read-bytes`, `:max-search-file-bytes`, and
   `:max-search-results`. Sensible defaults are used when a limit is
   omitted.

   No sandboxing is enforced in this version. The caller is responsible
   for configuring the workspace root and for only granting access to
   paths the agent should be allowed to read."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.tool :as tool])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]))

(def default-max-read-bytes
  "Default upper bound on how many bytes `file/read` will slurp."
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

(def OutputSchema:String
  "All filesystem tools return a JSON or plain string."
  :string)

(defn- workspace-root->file
  "Turn an optional `workspace-root` string into a `java.io.File`.
   Falls back to the current working directory when nil or empty."
  [workspace-root]
  (if (seq workspace-root)
    (io/file workspace-root)
    (io/file ".")))

(defn- resolve-path
  "Resolve a (possibly relative) user path against the workspace root.
   Returns a `java.nio.file.Path`. No containment check is performed."
  [workspace-root user-path]
  (let [root (workspace-root->file workspace-root)]
    (.toPath (io/file root user-path))))

(defn- path->str
  "Convert a `Path` to a normalized string."
  [^Path path]
  (str path))

(defn- safe-int
  "Coerce a value to a positive integer, returning default if missing or invalid."
  [value default]
  (let [v (if (integer? value)
            value
            (try (Long/parseLong (str value))
                 (catch Throwable _ default)))]
    (if (pos? v) v default)))

(defn- read-text
  "Read up to `max-read-bytes` bytes from `path` as UTF-8 text. Optional
   `offset` skips that many characters; `limit` caps returned characters.
   Returns a string. Throws on binary / decoding errors or missing file."
  [^Path path offset limit max-read-bytes]
  (let [bytes (Files/readAllBytes path)
        _ (when (> (count bytes) max-read-bytes)
            (throw (ex-info (format "File too large: %d bytes (limit %d)"
                                    (count bytes) max-read-bytes)
                            {:path (path->str path)
                             :size (count bytes)})))
        text  (String. ^bytes bytes StandardCharsets/UTF_8)
        off   (max 0 (safe-int offset 0))
        lim   (safe-int limit (count text))
        end   (min (+ off lim) (count text))]
    (subs text off end)))

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
      (throw (ex-info "Path is not a directory" {:path (path->str path)})))))

(defn- do-file-info
  "Return metadata for `path` as an EDN map."
  [^Path path]
  (let [f (.toFile path)]
    {:path          (path->str path)
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
    "Read the contents of a UTF-8 text file. Optionally skip `offset` characters and return at most `limit` characters. Paths are resolved against the configured workspace root. The total file size is capped by the registry's `:max-read-bytes` setting.")
  (-input-schema [_] InputSchema:ReadFile)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args]
    (try
      (let [path (resolve-path workspace-root (:path args))
            offset (:offset args)
            limit (:limit args)]
        (json/generate-string
         {:path      (path->str path)
          :size      (Files/size path)
          :truncated (boolean (when limit (> limit 0)))
          :content   (read-text path offset limit max-read-bytes)}))
      (catch Throwable t
        (error-result t)))))

(deftype ListDirectoryTool [workspace-root]
  tool/Tool
  (-name [_] "file/list")
  (-description [_]
    "List the files and directories inside a directory. Returns a JSON object with an `entries` array; each entry has `name` and `type` (`file`, `directory`, or `other`).")
  (-input-schema [_] InputSchema:Path)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args]
    (try
      (json/generate-string
       {:entries (do-list-directory (resolve-path workspace-root (:path args)))})
      (catch Throwable t
        (error-result t)))))

(deftype FileInfoTool [workspace-root]
  tool/Tool
  (-name [_] "file/info")
  (-description [_]
    "Return metadata for a path: whether it exists, its type (`file`, `directory`, or `other`), size in bytes, and last modified timestamp.")
  (-input-schema [_] InputSchema:Path)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args]
    (try
      (json/generate-string
       (do-file-info (resolve-path workspace-root (:path args))))
      (catch Throwable t
        (error-result t)))))

(deftype SearchFilesTool [workspace-root max-search-file-bytes default-max-search-results]
  tool/Tool
  (-name [_] "file/search")
  (-description [_]
    "Recursively search files under a directory for a regex pattern. Returns up to `max-results` matches as JSON objects with `file`, `line`, and `text`. Files larger than the registry's `:max-search-file-bytes` setting are skipped.")
  (-input-schema [_] InputSchema:SearchFiles)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args]
    (try
        (json/generate-string
       (do-search-files (resolve-path workspace-root (:path args))
                        (:pattern args)
                        (:max-results args)
                        max-search-file-bytes
                        default-max-search-results))
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

(defn filesystem-registry
  "Return a map of filesystem tool name -> Tool instance.

   Accepts an optional `opts` map with:
     :workspace-root          — root for resolving relative paths
     :max-read-bytes          — cap for `file/read` (default 256 KB)
     :max-search-file-bytes   — per-file cap for `file/search`
                                   (default 128 KB)
     :max-search-results      — default hit cap for `file/search`
                                   (default 100)

   When `:workspace-root` is omitted, the current working directory is
   used. No path containment is enforced."
  ([] (filesystem-registry {}))
  ([{:keys [workspace-root
            max-read-bytes
            max-search-file-bytes
            max-search-results]}]
   {"file/read"   (read-file workspace-root (or max-read-bytes default-max-read-bytes))
    "file/list"   (list-directory workspace-root)
    "file/info"   (file-info workspace-root)
    "file/search" (search-files workspace-root
                                (or max-search-file-bytes default-max-search-file-bytes)
                                (or max-search-results default-max-search-results))}))
