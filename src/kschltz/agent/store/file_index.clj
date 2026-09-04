(ns kschltz.agent.store.file-index
  "Workspace file index + edit log on top of StoreEngine.

   The filesystem remains the source of truth. This index is advisory:
   a failed index write never fails a file mutation, and a stale hash
   still fails `file_patch`."
  (:require [clojure.string :as str]
            [kschltz.agent.store.protocol :as store]
            [kschltz.agent.tools.file-path :as fpath]
            [kschltz.agent.tools.file-safety :as fs]
            [malli.core :as m]
            [malli.instrument :as mi])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]))

(def default-max-content-bytes
  "Cap on extracted text stored per file (same order as file_search)."
  (* 128 1024))

(defprotocol FileIndex
  "Query + mutate the workspace index. Implementations close over a
   StoreEngine and never expose JDBC."
  (-upsert-file! [idx entry]
    "Insert or replace a file_index row.")
  (-record-edit! [idx edit]
    "Append a file_edits row.")
  (-lookup [idx path]
    "Return the file_index row or nil.")
  (-search [idx opts]
    "Search indexed content. `opts` needs `:path-prefix`, `:pattern`,
     `:max-results`. Returns {:file :line :text} hits.")
  (-edits [idx opts]
    "Recent edits, optionally filtered by `:path`.")
  (-indexed-under? [idx path-prefix]
    "True when at least one indexed file lives under path-prefix.")
  (-close [idx]
    "Release the underlying store if this index owns it."))

(defn file-index?
  [x]
  (satisfies? FileIndex x))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- under-prefix?
  [path prefix]
  (or (= path prefix)
      (str/starts-with? (str path) (str prefix "/"))))

(defn- compile-pattern
  [pattern]
  (re-pattern (str "(?i)" pattern)))

(defn- content-hits
  [path content re max-results]
  (when (string? content)
    (into []
          (comp
           (map-indexed (fn [idx line]
                          (when (re-find re line)
                            {:file path
                             :line (inc idx)
                             :text (str/trim line)})))
           (filter some?)
           (take max-results))
          (str/split-lines content))))

(defn- file-row
  [{:keys [path sha256 size mtime content indexed-at]}]
  {:path path
   :sha256 sha256
   :size size
   :mtime mtime
   :content content
   :indexed-at (or indexed-at (now-ms))})

(defn- edit-row
  [{:keys [id path tool sha256-before sha256-after start-line end-line ts]}]
  {:id (or id (str (random-uuid)))
   :path path
   :tool tool
   :sha256-before sha256-before
   :sha256-after sha256-after
   :start-line start-line
   :end-line end-line
   :ts (or ts (now-ms))})

(defrecord StoreFileIndex [engine max-content-bytes]
  FileIndex
  (-upsert-file! [_ entry]
    (store/-upsert! engine :file_index [:path] (file-row entry))
    entry)
  (-record-edit! [_ edit]
    (let [row (edit-row edit)]
      (store/-insert! engine :file_edits row)
      row))
  (-lookup [_ path]
    (first (store/-select engine :file_index {:where {:path path}})))
  (-search [_ {:keys [path-prefix pattern max-results]
               :or {max-results 100}}]
    (let [re (compile-pattern pattern)
          rows (store/-select engine :file_index
                              {:where (when path-prefix
                                        {:path-prefix path-prefix})})
          hits (volatile! [])]
      (doseq [row rows
              :while (< (count @hits) max-results)]
        (when (or (nil? path-prefix)
                  (under-prefix? (:path row) path-prefix))
          (vswap! hits into
                  (content-hits (:path row) (:content row) re
                                (- max-results (count @hits))))))
      @hits))
  (-edits [_ {:keys [path limit] :or {limit 50}}]
    (store/-select engine :file_edits
                   {:where (when path {:path path})
                    :order [:ts]
                    :desc true
                    :limit limit}))
  (-indexed-under? [_ path-prefix]
    (boolean
     (seq (store/-select engine :file_index
                         {:where (when path-prefix {:path-prefix path-prefix})
                          :limit 1}))))
  (-close [_]
    nil))

(defn file-index
  "Build a FileIndex over `store` (a StoreEngine)."
  ([store] (file-index store {}))
  ([store {:keys [max-content-bytes]}]
   (->StoreFileIndex store (or max-content-bytes default-max-content-bytes))))

(m/=> file-index
      [:function
       [:=> [:cat [:fn store/store-engine?]] [:fn file-index?]]
       [:=> [:cat [:fn store/store-engine?] :map] [:fn file-index?]]])

(defn entry-from-disk
  "Build a file_index row from a live path. Content is omitted when the
   file is missing, binary-unreadable, or over `max-content-bytes`."
  [^Path path max-content-bytes]
  (let [f (.toFile path)]
    (when (.isFile f)
      (let [bytes (try (Files/readAllBytes path) (catch Throwable _ nil))
            content (when (and bytes (<= (count bytes) max-content-bytes))
                      (try (String. ^bytes bytes StandardCharsets/UTF_8)
                           (catch Throwable _ nil)))]
        {:path (fpath/path->str path)
         :sha256 (when bytes (fs/sha256 bytes))
         :size (when bytes (count bytes))
         :mtime (.lastModified f)
         :content content
         :indexed-at (now-ms)}))))

(defn record-mutation!
  "Advisory: upsert the file and append an edit. Never throws to the
   caller — index failures are swallowed after the filesystem commit."
  [idx {:keys [path tool sha256-before sha256-after
               start-line end-line content size mtime]}]
  (when (and idx path)
    (try
      (let [p (fpath/resolve-path nil path)
            disk (entry-from-disk p (or (:max-content-bytes idx) default-max-content-bytes))
            entry (merge {:path path
                          :sha256 sha256-after
                          :size size
                          :mtime mtime
                          :content content
                          :indexed-at (now-ms)}
                         (when disk
                           (select-keys disk [:sha256 :size :mtime :content])))]
        (-upsert-file! idx entry)
        (-record-edit! idx {:path path
                            :tool tool
                            :sha256-before sha256-before
                            :sha256-after (or sha256-after (:sha256 entry))
                            :start-line start-line
                            :end-line end-line}))
      (catch Throwable _
        nil))))

(defn reindex-tree!
  "Walk `root` and upsert every regular file that is not blocked and
   not over `max-file-bytes`. Returns {:files :skipped}."
  [idx ^Path root blocked-paths max-file-bytes]
  (let [files (volatile! 0)
        skipped (volatile! 0)
        max-content (or (:max-content-bytes idx) default-max-content-bytes)]
    (doseq [^File f (file-seq (.toFile root))
            :when (.isFile f)]
      (let [p (.toPath f)]
        (if (or (fs/blocked-path? p blocked-paths)
                (> (.length f) max-file-bytes))
          (vswap! skipped inc)
          (if-let [entry (entry-from-disk p max-content)]
            (do (-upsert-file! idx entry)
                (vswap! files inc))
            (vswap! skipped inc)))))
    {:files @files :skipped @skipped}))

(defn instrument! []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.store.file-index)]}))

(instrument!)
