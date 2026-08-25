(ns kschltz.agent.tools.clojure-lint
  "Bounded clj-kondo diagnostics for edited Clojure/EDN files."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.clojure-impl :as impl])
  (:import [java.io File]
           [java.util.concurrent TimeUnit]))

(def InputSchema
  [:map {:closed true}
   [:paths [:vector {:min 1 :max 50} [:string {:min 1}]]]])

(defn- stream-text
  [stream]
  (future
    (with-open [reader (java.io.BufferedReader.
                        (java.io.InputStreamReader. stream "UTF-8"))]
      (let [buf (char-array 4096)
            out (StringBuilder.)]
        (loop []
          (let [n (.read reader buf)]
            (when (pos? n)
              (when (< (.length out) (* 1024 1024))
                (.append out buf 0
                         (min n (- (* 1024 1024) (.length out)))))
              (recur))))
        (str out)))))

(defn- default-runner
  [workspace-root paths]
  (let [command (into ["clj-kondo" "--lint"]
                      (concat paths
                              ["--config" "{:output {:format :json}}"]))
        builder (ProcessBuilder. ^java.util.List command)
        _ (.directory builder
                      (if (seq workspace-root)
                        (File. workspace-root)
                        (File. ".")))
        process (.start builder)
        stdout (stream-text (.getInputStream process))
        stderr (stream-text (.getErrorStream process))
        completed? (.waitFor process 30 TimeUnit/SECONDS)]
    (when-not completed?
      (.destroyForcibly process))
    (let [out @stdout
          err @stderr]
      (if-not completed?
        {:ok false
         :error :diagnostics-timeout
         :message "clj-kondo exceeded 30 seconds"}
        (try
          (let [parsed (json/parse-string out true)]
            {:ok true
             :engine "clj-kondo"
             :exit-code (.exitValue process)
             :findings (vec (:findings parsed))
             :summary (:summary parsed)
             :stderr (not-empty (str/trim err))})
          (catch Throwable _
            {:ok false
             :error :diagnostics-invalid-output
             :exit-code (.exitValue process)
             :stdout out
             :stderr err}))))))

(defn- error-result
  [t]
  (let [data (if (instance? clojure.lang.ExceptionInfo t) (ex-data t) {})]
    (json/generate-string
     (merge {:ok false
             :error (or (:error data) :diagnostics-error)
             :message (or (ex-message t) (.getName (class t)))}
            (dissoc data :error)))))

(deftype ClojureLintTool [workspace-root blocked-paths runner]
  tool/Tool
  (-name [_] "clojure_lint")
  (-description [_]
    "Run bounded clj-kondo diagnostics on up to 50 Clojure/EDN files after editing. Paths use the same canonical workspace, blocked-path, symlink, and file-type policy as structured edits. Returns structured findings and summary; it never modifies files.")
  (-input-schema [_] InputSchema)
  (-output-schema [_] :string)
  (-invoke [_ {:keys [paths]} _ctx]
    (try
      (let [resolved (mapv #(impl/resolve-path workspace-root %
                                               blocked-paths)
                           paths)]
        (json/generate-string
         (runner workspace-root (mapv impl/path->str resolved))))
      (catch java.io.IOException t
        (json/generate-string
         {:ok false
          :error :diagnostics-unavailable
          :message (or (ex-message t) "clj-kondo is unavailable")}))
      (catch Throwable t
        (error-result t)))))

(defn clojure-lint
  ([]
   (clojure-lint nil {}))
  ([workspace-root]
   (clojure-lint workspace-root {}))
  ([workspace-root {:keys [blocked-paths runner]}]
   (->ClojureLintTool workspace-root
                      blocked-paths
                      (or runner default-runner))))
