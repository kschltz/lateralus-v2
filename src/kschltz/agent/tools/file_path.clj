(ns kschltz.agent.tools.file-path
  "Tiny path helpers shared by `file-system`, `file-write`, and the
   read-only filesystem tools.

   Kept in its own namespace so that `file-write` can require
   `file-system` (for `OutputSchema:String`) without creating a
   circular dependency back into `file-write`. Anything here is
   pure — no I/O, no JVM side effects beyond `java.io.File` and
   `java.nio.file.Path` construction."
  (:require [clojure.java.io :as io])
  (:import [java.io File]
           [java.nio.file Path]))

(defn workspace-root->file
  "Turn an optional `workspace-root` string into a `java.io.File`.
   Falls back to the current working directory when nil or empty."
  [workspace-root]
  (if (seq workspace-root)
    (io/file workspace-root)
    (io/file ".")))

(defn resolve-path
  "Resolve a user path against the workspace root. Absolute paths are
   preserved; relative paths are resolved under the workspace root. The
   result is normalized so that parent references collapse."
  [workspace-root user-path]
  (let [user-file (io/file user-path)]
    (.normalize
     (.toPath (if (.isAbsolute user-file)
                user-file
                (io/file (workspace-root->file workspace-root) user-path))))))

(defn path->str
  "Convert a `Path` to a normalized string."
  [^Path path]
  (str (.normalize path)))
