(ns kschltz.agent.memory.store.file
  "File-backed session store primitives.

   Reads and writes line-oriented EDN session files. Session file
   reads use `clojure.edn/read` with a safe reader (no default readers)
   to avoid evaluating code from untrusted disk contents."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File FileWriter PushbackReader]))

(defn session-dir
  "Return the File for a session directory."
  ^File [root session-id]
  (io/file root session-id))

(defn ensure-dir!
  [^File dir]
  (.mkdirs dir))

(defn messages-file [^File dir]
  (io/file dir "messages.edn"))

(defn index-file [^File dir]
  (io/file dir "index.edn"))

(def ^:private safe-read-opts
  "EDN opts that disables default tagged-literal expansion."
  {:default (fn [tag val]
              (throw (ex-info "Unsupported tagged literal"
                              {:tag tag :value val})))})

(defn- safe-read
  "Read a single EDN value with a safe reader."
  [s]
  (edn/read safe-read-opts (PushbackReader. (java.io.StringReader. s))))

(defn read-lines
  "Read EDN objects one per line from a file, returning a vector."
  [^File f]
  (if (.exists ^java.io.File f)
    (with-open [rdr (io/reader f)]
      (vec (for [line (line-seq rdr)
                 :let [trimmed (str/trim line)]
                 :when (seq trimmed)]
             (safe-read trimmed))))
    []))

(defn append-line!
  "Append a single EDN map as one line to a file."
  [^File f m]
  (ensure-dir! (.getParentFile f))
  (with-open [w (FileWriter. f true)]
    (.write w (pr-str m))
    (.write w "\n")))

(defn write-file!
  "Overwrite a file with an EDN value."
  [^File f v]
  (ensure-dir! (.getParentFile f))
  (spit f (pr-str v)))

(defn read-index
  "Read the first EDN value from the session index file, coercing to a map."
  [^File dir]
  (let [f (index-file dir)]
    (if (.exists ^java.io.File f)
      (let [v (first (read-lines f))]
        (if (map? v) v {}))
      {})))

(defn write-index!
  "Overwrite the session index file with an EDN value."
  [^File dir index]
  (write-file! (index-file dir) index))
