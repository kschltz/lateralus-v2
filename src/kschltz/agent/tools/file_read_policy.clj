(ns kschltz.agent.tools.file-read-policy
  "Shared coercion, containment, and error helpers for read-side file tools."
  (:require [cheshire.core :as json]
            [kschltz.agent.tools.file-path :as fpath]
            [kschltz.agent.tools.file-safety :as fs]))

(defn safe-int
  "Coerce a value to a positive integer, returning default when invalid."
  [value default]
  (let [v (if (integer? value)
            value
            (try
              (Long/parseLong (str value))
              (catch Throwable _ default)))]
    (if (pos? v) v default)))

(defn byte-count
  "Return the UTF-8 byte length of `s`."
  [^String s]
  (alength (.getBytes s "UTF-8")))

(defn resolve-readable-path
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

(defn error-result
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
