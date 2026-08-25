(ns kschltz.agent.tools.clojure-impl
  "Internal rewrite-clj helpers for the Clojure structured-editing tools.
   This namespace is not part of the public tool API; it is imported by
   kschltz.agent.tools.clojure."
  (:require [clojure.string :as str]
            [kschltz.agent.tools.file-path :as fpath]
            [kschltz.agent.tools.file-safety :as fs]
            [rewrite-clj.zip :as z]
            [rewrite-clj.node :as n])
  (:import [java.nio.file Files Path]
           [java.nio.charset StandardCharsets]))

(def default-max-read-bytes (* 256 1024))

(defn resolve-path
  "Resolve and validate a Clojure/EDN target inside `workspace-root`.

   Canonicalization follows existing symlinks, so a path that appears to be
   inside the workspace but resolves outside it is rejected. Blocked segments
   are checked on both the requested and canonical paths."
  ([workspace-root user-path]
   (resolve-path workspace-root user-path fs/default-blocked-paths))
  ([workspace-root user-path blocked-paths]
   (let [requested (fpath/resolve-path workspace-root user-path)
         root (fs/canonical-path
               (.toPath (fpath/workspace-root->file workspace-root)))
         canonical (fs/canonical-path requested)]
     (cond
       (not (fs/within-write-dir? root canonical))
       (throw (ex-info "Path resolves outside the configured workspace"
                       {:error :outside-workspace
                        :path (fpath/path->str requested)}))

       (or (fs/blocked-path? requested blocked-paths)
           (fs/blocked-path? canonical blocked-paths))
       (throw (ex-info "Path contains a blocked segment"
                       {:error :blocked-path
                        :path (fpath/path->str requested)}))

       (not (fs/clojure-file? canonical))
       (throw (ex-info "Structured Clojure tools require a Clojure/EDN file"
                       {:error :wrong-file-type
                        :path (fpath/path->str requested)
                        :use-tool "file_read/file_update"}))

       :else canonical))))

(defn path->str [^Path path] (str path))

(defn read-source [^Path path max-read-bytes]
  (let [bytes (Files/readAllBytes path)
        size  (count bytes)]
    (when (> size max-read-bytes)
      (throw (ex-info (format "File too large: %d bytes (limit %d)" size max-read-bytes)
                      {:kind :clojure-tool/error :path (path->str path) :size size})))
    (String. ^bytes bytes StandardCharsets/UTF_8)))

(defn parse-or-fail [source path]
  (try
    (z/of-string* source)
    (catch Throwable t
      (throw (ex-info (format "Failed to parse Clojure source: %s" (ex-message t))
                      {:kind :clojure-tool/error :path path :reason :parse-failed})))))

(defn root-string-or-fail [zloc path]
  (let [out (z/root-string zloc)]
    (try
      (z/of-string* out)
      out
      (catch Throwable t
        (throw (ex-info (format "Round-trip validation failed: %s" (ex-message t))
                        {:kind :clojure-tool/error :path path :reason :round-trip-failed :output out}))))))

(defn write-with-backup!
  "Commit `content` only when the file still matches `original`.

   The read/transform/write race is fenced under the same per-path lock used
   by the generic file tools. A successful replacement receives a timestamped
   byte-for-byte backup, atomic landing, and post-write verification."
  [^Path path ^String original ^String content]
  (let [original-bytes (.getBytes original StandardCharsets/UTF_8)
        sentinel (fs/staleness-sentinel path original-bytes)]
    (fs/with-path-lock (path->str path)
      (when (fs/check-staleness path sentinel)
        (throw (ex-info "File changed after it was read"
                        {:error :stale-file
                         :path (path->str path)
                         :expected-sha256 (:sha256 sentinel)})))
      (let [backup (fs/make-backup! path)]
        (fs/write-atomically! path content)
        (let [written (Files/readAllBytes path)
              expected (.getBytes content StandardCharsets/UTF_8)]
          (when-not (java.util.Arrays/equals ^bytes expected ^bytes written)
            (throw (ex-info "Atomic write verification failed"
                            {:error :write-verify-failed
                             :path (path->str path)})))
          {:path (path->str path)
           :backup-path backup
           :previous-sha256 (:sha256 sentinel)
           :sha256 (fs/sha256 written)})))))

(defn top-level-forms [forms-zloc]
  (loop [zloc (z/down forms-zloc) acc []]
    (if-not zloc acc (recur (z/right zloc) (conj acc zloc)))))

(defn ns-form [forms-zloc]
  (first (filter (fn [zloc]
                   (let [first-child (z/down zloc)]
                     (and (= :token (z/tag first-child)) (= 'ns (z/sexpr first-child)))))
                 (top-level-forms forms-zloc))))

(defn find-top-level-def [forms-zloc name]
  (first (filter (fn [zloc]
                   (let [first-child (z/down zloc)
                         name-child  (when first-child (z/right first-child))]
                     (and first-child
                          (= :token (z/tag first-child))
                          (#{'def 'defn 'defn- 'defmacro 'defmulti 'defonce 'defrecord 'deftype}
                           (z/sexpr first-child))
                          name-child
                          (= :token (z/tag name-child))
                          (= name (z/sexpr name-child)))))
                 (top-level-forms forms-zloc))))

(defn keyword-node? [zloc] (keyword? (z/sexpr zloc)))

(defn find-keyword-child [zloc kw]
  (loop [child (z/down zloc)]
    (cond
      (nil? child) nil
      (and (keyword-node? child) (= kw (z/sexpr child))) child
      (#{:list :vector} (z/tag child))
      (let [first-child (z/down child)]
        (if (and (keyword-node? first-child) (= kw (z/sexpr first-child)))
          first-child
          (recur (z/right child))))
      :else (recur (z/right child)))))

(defn libspecs-from-section [section-zloc]
  (loop [zloc section-zloc acc []]
    (if (or (nil? zloc) (keyword-node? zloc))
      acc
      (recur (z/right zloc)
             (if (seq (str/trim (z/string zloc)))
               (conj acc (str/trim (z/string zloc)))
               acc)))))

(defn libspec-matches? [zloc libspec]
  (cond
    (= :token (z/tag zloc)) (= libspec (z/sexpr zloc))
    (= :vector (z/tag zloc))
    (let [v (z/sexpr zloc)]
      (and (seq v) (symbol? (first v)) (= libspec (first v))))
    :else false))

(defn require-exists? [require-section libspec]
  (loop [zloc require-section]
    (cond
      (nil? zloc) false
      (libspec-matches? zloc libspec) true
      :else (recur (z/right zloc)))))

(defn first-body-node [def-zloc]
  (let [name-zloc (z/right (z/down def-zloc))]
    (loop [zloc (z/right name-zloc) seen-arg-vector? false]
      (cond
        (nil? zloc) nil
        (and (= :token (z/tag zloc)) (string? (z/sexpr zloc))) (recur (z/right zloc) seen-arg-vector?)
        (= :meta (z/tag zloc)) (recur (z/right zloc) seen-arg-vector?)
        (and (not seen-arg-vector?) (= :vector (z/tag zloc))) (recur (z/right zloc) true)
        :else zloc))))

(defn remove-all-right [zloc]
  (if-let [right (z/right zloc)]
    (recur (z/remove right))
    zloc))

(defn require-libspec-node [libspec alias-sym]
  (if alias-sym
    (n/vector-node [(n/token-node libspec)
                    (n/whitespace-node " ")
                    (n/keyword-node :as)
                    (n/whitespace-node " ")
                    (n/token-node alias-sym)])
    (n/token-node libspec)))

(defn new-require-section-node [libspec-node]
  (n/list-node [(n/keyword-node :require)
                (n/whitespace-node " ")
                libspec-node]))
