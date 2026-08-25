(ns kschltz.agent.tools.file-glob
  "Bounded, deterministic workspace file discovery for agent harnesses."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.file-path :as fpath]
            [kschltz.agent.tools.file-safety :as fs])
  (:import [java.nio.file FileSystems Files Path]))

(def default-max-results 500)

(def InputSchema
  [:map {:closed true}
   [:pattern [:string {:min 1}]]
   [:path {:optional true} [:string {:min 1}]]
   [:max-results {:optional true} [:int {:min 1}]]])

(defn- error-result
  [t]
  (let [data (if (instance? clojure.lang.ExceptionInfo t) (ex-data t) {})]
    (json/generate-string
     (merge {:ok false
             :error (or (:error data) :filesystem-error)
             :message (or (ex-message t) (.getName (class t)))}
            (dissoc data :error)))))

(defn- matchers
  [pattern]
  (try
    (let [fsys (FileSystems/getDefault)
          primary (.getPathMatcher fsys (str "glob:" pattern))
          root-pattern (when (str/starts-with? pattern "**/")
                         (.getPathMatcher fsys
                                          (str "glob:" (subs pattern 3))))]
      (cond-> [primary] root-pattern (conj root-pattern)))
    (catch Throwable t
      (throw (ex-info (str "Invalid glob pattern: " (ex-message t))
                      {:error :invalid-pattern
                       :pattern pattern})))))

(defn- resolve-root
  [workspace-root user-path blocked-paths]
  (let [workspace (fs/canonical-path
                   (.toPath (fpath/workspace-root->file workspace-root)))
        requested (fpath/resolve-path workspace-root (or user-path "."))
        canonical (fs/canonical-path requested)]
    (cond
      (not (fs/within-write-dir? workspace canonical))
      (throw (ex-info "Glob root resolves outside the configured workspace"
                      {:error :outside-workspace
                       :path (fpath/path->str requested)}))

      (or (fs/blocked-path? requested blocked-paths)
          (fs/blocked-path? canonical blocked-paths))
      (throw (ex-info "Glob root contains a blocked segment"
                      {:error :blocked-path
                       :path (fpath/path->str requested)}))

      (not (Files/isDirectory canonical (make-array java.nio.file.LinkOption 0)))
      (throw (ex-info "Glob root is not a directory"
                      {:error :not-directory
                       :path (fpath/path->str canonical)}))

      :else canonical)))

(defn- relative-path
  [^Path root ^Path path]
  (-> (.relativize root path)
      str
      (str/replace "\\" "/")))

(defn- discover
  [workspace-root blocked-paths pattern user-path max-results]
  (let [root (resolve-root workspace-root user-path blocked-paths)
        matchers (matchers pattern)]
    (with-open [stream (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
      (let [matches
            (->> (.iterator stream)
                 iterator-seq
                 (filter #(Files/isRegularFile ^Path %
                                               (make-array java.nio.file.LinkOption 0)))
                 (remove #(Files/isSymbolicLink ^Path %))
                 (remove #(fs/blocked-path? ^Path % blocked-paths))
                 (map (fn [^Path path]
                        {:relative (relative-path root path)
                         :path path}))
                 (filter (fn [{:keys [relative]}]
                           (let [candidate (.getPath (FileSystems/getDefault)
                                                     relative
                                                     (make-array String 0))]
                             (some #(.matches % candidate) matchers))))
                 (sort-by :relative)
                 (mapv (fn [{:keys [relative path]}]
                         {:path relative
                          :size (Files/size ^Path path)})))
            total (count matches)
            selected (subvec matches 0 (min total max-results))]
        {:root (fpath/path->str root)
         :pattern pattern
         :matches selected
         :total-matches total
         :truncated (< (count selected) total)}))))

(deftype FileGlobTool [workspace-root blocked-paths default-max-results]
  tool/Tool
  (-name [_] "file_glob")
  (-description [_]
    "Discover workspace files by glob pattern (for example `**/*.clj`). Results are relative, sorted, bounded, and include byte sizes. Canonical workspace containment and blocked paths are enforced; directory symlinks are not followed.")
  (-input-schema [_] InputSchema)
  (-output-schema [_] :string)
  (-invoke [_ args _ctx]
    (try
      (json/generate-string
       (discover workspace-root
                 blocked-paths
                 (:pattern args)
                 (:path args)
                 (or (:max-results args) default-max-results)))
      (catch Throwable t
        (error-result t)))))

(defn file-glob
  ([]
   (file-glob nil {}))
  ([workspace-root]
   (file-glob workspace-root {}))
  ([workspace-root {:keys [blocked-paths max-results]}]
   (->FileGlobTool workspace-root
                   (or blocked-paths fs/default-blocked-paths)
                   (or max-results default-max-results))))
