(ns kschltz.agent.tools.file-patch
  "Hash-anchored line-range patches for deterministic agent file editing."
  (:require [cheshire.core :as json]
            [kschltz.agent.store.file-index :as file-index]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.file-path :as fpath]
            [kschltz.agent.tools.file-safety :as fs])
  (:import [java.nio ByteBuffer]
           [java.nio.charset CodingErrorAction StandardCharsets]
           [java.nio.file Files]))

(def Patch
  [:map {:closed true}
   [:start-line [:int {:min 1}]]
   [:end-line [:int {:min 0}]]
   [:replacement :string]])

(def InputSchema
  [:map {:closed true}
   [:path [:string {:min 1}]]
   [:expected-sha256 [:string {:min 64 :max 64}]]
   [:patches [:vector {:min 1} Patch]]])

(defn- error-result
  [t]
  (let [data (if (instance? clojure.lang.ExceptionInfo t) (ex-data t) {})]
    (json/generate-string
     (merge {:ok false
             :error (or (:error data) :filesystem-error)
             :message (or (ex-message t) (.getName (class t)))}
            (dissoc data :error)))))

(defn- resolve-target
  [workspace-root user-path blocked-paths]
  (let [requested (fpath/resolve-path workspace-root user-path)
        canonical (fs/canonical-path requested)
        root (fs/canonical-path
              (.toPath (fpath/workspace-root->file workspace-root)))]
    (cond
      (not (fs/within-write-dir? root canonical))
      (throw (ex-info "Patch target resolves outside the configured workspace"
                      {:error :outside-workspace
                       :path (fpath/path->str requested)}))

      (or (fs/blocked-path? requested blocked-paths)
          (fs/blocked-path? canonical blocked-paths))
      (throw (ex-info "Patch target contains a blocked segment"
                      {:error :blocked-path
                       :path (fpath/path->str requested)}))

      (not (Files/isRegularFile canonical
                                (make-array java.nio.file.LinkOption 0)))
      (throw (ex-info "Patch target is not a regular file"
                      {:error :file-not-found
                       :path (fpath/path->str canonical)}))

      :else canonical)))

(defn- decode-utf8
  [^bytes bytes]
  (try
    (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))]
      (str (.decode decoder (ByteBuffer/wrap bytes))))
    (catch Throwable _
      (throw (ex-info "Patch target is not valid UTF-8 text"
                      {:error :binary-file})))))

(defn- line-starts
  "Offsets for each real line. Empty input has zero lines."
  [^String source]
  (if (empty? source)
    []
    (loop [idx 0 starts [0]]
      (let [newline (.indexOf source "\n" idx)]
        (if (neg? newline)
          starts
          (let [next (inc newline)]
            (if (< next (count source))
              (recur next (conj starts next))
              starts)))))))

(defn- line-end-offset
  "Exclusive offset after line `line-no`, including its newline when present."
  [^String source starts line-no]
  (if (= line-no (count starts))
    (count source)
    (nth starts line-no)))

(defn- patch-span
  [^String source starts {:keys [start-line end-line replacement] :as patch}]
  (let [line-count (count starts)
        insertion? (= end-line (dec start-line))]
    (cond
      (> start-line (inc line-count))
      (throw (ex-info "Patch start line is beyond end of file"
                      {:error :line-out-of-range
                       :patch patch
                       :line-count line-count}))

      (and (not insertion?) (< end-line start-line))
      (throw (ex-info "Patch end line must be start-line - 1 or later"
                      {:error :invalid-range
                       :patch patch}))

      (> end-line line-count)
      (throw (ex-info "Patch end line is beyond end of file"
                      {:error :line-out-of-range
                       :patch patch
                       :line-count line-count}))

      :else
      (let [start-offset (if (= start-line (inc line-count))
                           (count source)
                           (nth starts (dec start-line)))
            end-offset (if insertion?
                         start-offset
                         (line-end-offset source starts end-line))]
        {:start start-offset
         :end end-offset
         :replacement replacement
         :start-line start-line
         :end-line end-line}))))

(defn- validate-spans
  [spans]
  (let [sorted (sort-by (juxt :start :end) spans)]
    (loop [prior-end -1 remaining sorted]
      (when-let [span (first remaining)]
        (when (< (:start span) prior-end)
          (throw (ex-info "Patch ranges overlap"
                          {:error :overlap
                           :start-line (:start-line span)
                           :end-line (:end-line span)})))
        (recur (:end span) (rest remaining))))
    (vec sorted)))

(defn- apply-spans
  [source spans]
  (reduce (fn [result {:keys [start end replacement]}]
            (str (subs result 0 start)
                 replacement
                 (subs result end)))
          source
          (sort-by :start > spans)))

(defn- apply-patch!
  [workspace-root blocked-paths max-write-bytes clojure-guard?
   path expected-sha256 patches file-index]
  (let [target (resolve-target workspace-root path blocked-paths)
        target-str (fpath/path->str target)]
    (fs/with-path-lock target-str
      (let [before-bytes (Files/readAllBytes target)
            before-sha (fs/sha256 before-bytes)]
        (when (not= expected-sha256 before-sha)
          (throw (ex-info "Patch is based on a stale file snapshot"
                          {:error :stale-file
                           :expected-sha256 expected-sha256
                           :actual-sha256 before-sha})))
        (when (> (count before-bytes) max-write-bytes)
          (throw (ex-info "Patch target exceeds the configured size limit"
                          {:error :file-too-large
                           :limit max-write-bytes})))
        (when-let [bad (some #(fs/scan-omission-placeholders (:replacement %))
                            patches)]
          (throw (ex-info "Patch replacement contains an omission placeholder"
                          {:error :omission-placeholder
                           :placeholder bad})))
        (let [source (decode-utf8 before-bytes)
              starts (line-starts source)
              spans (->> patches
                         (mapv #(patch-span source starts %))
                         validate-spans)
              result (apply-spans source spans)
              result-bytes (.getBytes result StandardCharsets/UTF_8)]
          (when (> (count result-bytes) max-write-bytes)
            (throw (ex-info "Patched file exceeds the configured size limit"
                            {:error :file-too-large
                             :limit max-write-bytes})))
          (when (and clojure-guard?
                     (fs/clojure-file? target)
                     (not (:ok (fs/clojure-round-trip? result))))
            (throw (ex-info "Patch would make Clojure/EDN source invalid"
                            {:error :clojure-round-trip-failed})))
          (when (= source result)
            (throw (ex-info "Patch would not change the file"
                            {:error :no-op})))
          (let [backup (fs/make-backup! target)]
            (fs/write-atomically! target result)
            (let [written (Files/readAllBytes target)]
              (when-not (java.util.Arrays/equals ^bytes result-bytes ^bytes written)
                (throw (ex-info "Patch write verification failed"
                                {:error :write-verify-failed})))
              (let [digest (fs/sha256 written)
                    payload {:path target-str
                             :changed true
                             :patches-applied (count spans)
                             :backup-path backup
                             :previous-sha256 before-sha
                             :sha256 digest}]
                (when file-index
                  (file-index/record-mutation! file-index
                                               {:path target-str
                                                :tool "file_patch"
                                                :sha256-before before-sha
                                                :sha256-after digest
                                                :content result
                                                :start-line (:start-line (first spans))
                                                :end-line (:end-line (last spans))}))
                payload))))))))

(deftype FilePatchTool [workspace-root blocked-paths max-write-bytes clojure-guard? file-index]
  tool/Tool
  (-name [_] "file_patch")
  (-description [_]
    "Apply one or more 1-based line-range patches to the exact SHA-256 snapshot returned by `file_read`. `end-line = start-line - 1` inserts before `start-line`; otherwise the inclusive line range is replaced. Stale, overlapping, out-of-range, binary, oversized, omission-placeholder, and invalid Clojure patches perform zero writes. Successful commits are locked, backed up, atomic, and verified.")
  (-input-schema [_] InputSchema)
  (-output-schema [_] :string)
  (-invoke [_ args _ctx]
    (try
      (json/generate-string
       (apply-patch! workspace-root blocked-paths max-write-bytes clojure-guard?
                     (:path args)
                     (:expected-sha256 args)
                     (:patches args)
                     file-index))
      (catch Throwable t
        (error-result t)))))

(defn file-patch
  ([]
   (file-patch nil {}))
  ([workspace-root]
   (file-patch workspace-root {}))
  ([workspace-root {:keys [blocked-paths max-write-bytes clojure-guard? file-index]}]
   (->FilePatchTool workspace-root
                    (or blocked-paths fs/default-blocked-paths)
                    (or max-write-bytes fs/default-max-write-bytes)
                    (if (some? clojure-guard?)
                      clojure-guard?
                      true)
                    file-index)))
