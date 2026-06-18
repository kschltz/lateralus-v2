(ns kschltz.agent.tools.clojure-impl
  "Internal rewrite-clj helpers for the Clojure structured-editing tools.
   This namespace is not part of the public tool API; it is imported by
   kschltz.agent.tools.clojure."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.zip :as z]
            [rewrite-clj.node :as n])
  (:import [java.io File]
           [java.nio.file Files Path StandardCopyOption]
           [java.nio.charset StandardCharsets]))

(def default-max-read-bytes (* 256 1024))

(defn workspace-root->file [workspace-root]
  (if (seq workspace-root) (io/file workspace-root) (io/file ".")))

(defn resolve-path [workspace-root user-path]
  (let [^File root-file (workspace-root->file workspace-root)
        ^File user-file (io/file user-path)
        ^Path root-path (.toPath root-file)
        ^Path user-path' (.toPath user-file)]
    (.normalize (if (.isAbsolute user-path')
                  user-path'
                  (.resolve root-path user-path')))))

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

(defn write-with-backup! [^Path path content]
  (let [file (.toFile path)
        bak  (io/file (str file ".bak"))]
    (when (.exists file)
      (.mkdirs (.getParentFile bak))
      (spit bak (slurp file :encoding "UTF-8") :encoding "UTF-8"))
    (spit file content :encoding "UTF-8")
    (path->str path)))

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
