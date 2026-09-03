(ns kschltz.agent.tools.file-write
 "Mutating filesystem Tool implementations: `file_write` and `file_update`.

   These two tools live in their own namespace so that the read-only
   `filesystem` namespace can stay under the project's per-file line
   budget while still owning the public `filesystem-registry` map.
   The behavior, schemas, and safety guarantees are identical to the
   previous in-`filesystem.clj` implementation:

     * workspace-root containment (skippable per-call via `:force`)
     * blocked-path refusal (`.git`, `target`, `node_modules`, ... —
       never skippable)
     * optional refusal of Clojure/EDN source files unless
       `:clj-override` is set
     * timestamped `.bak.<millis>` sidecar backup before mutation
     * atomic temp-file + `Files/move` landing
     * per-path lock that serializes edits across threads
     * `expected-sha256` staleness guard
     * round-trip-validate Clojure/EDN output when `:clojure-guard?`
       is enabled
     * `:omission-placeholder`, `:line-number-prefix`,
       `:overlap`, `:no-op`, `:ambiguous-match`, `:no-match`,
       `:count-mismatch`, `:stale-file`, `:file-too-large`,
       `:clojure-round-trip-failed`, `:write-verify-failed` errors
     * `:fuzzy` matching (EOL/BOM/Unicode-tolerant) when no exact
       hit exists"
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kschltz.agent.store.file-index :as file-index]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.file-path :as fpath]
            [kschltz.agent.tools.file-safety :as fs])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def ^:private OutputSchema:String
 "Both `file_write` and `file_update` return a JSON or plain string.
   Duplicated here as a private constant so this namespace does not
   have to `require` `kschltz.agent.tools.filesystem` (which would
   create a load-order cycle: filesystem -> file-write -> filesystem)."
  :string)

(def InputSchema:WriteFile
 "Input schema for `file_write`.

   `:expected-sha256`, when supplied, must match the SHA-256 of the
   file currently on disk (the caller's last-known view); a mismatch
   aborts with `:stale-file`. `:force` skips workspace-root
   containment only (never blocked-path refusal). `:clj-override`
   allows writing `.clj`/`.cljs`/`.cljc`/`.cljd`/`.edn` files even when
   the registry has `:refuse-clojure?` true."
  [:map
   [:path [:string {:min 1}]]
   [:content :string]
   [:create-dirs {:optional true} :boolean]
   [:create-only {:optional true} :boolean]
   [:expected-sha256 {:optional true} :string]
   [:force {:optional true} :boolean]
   [:clj-override {:optional true} :boolean]])

(def InputSchema:UpdateFile
 "Input schema for `file_update`.

   `:edits` is a vector of `{:old-text :new-text :replace-all
   :expected-occurrences}` maps. It may also be supplied as a JSON or
   EDN string (a compatibility seam for callers that serialize the
   edits), in which case it is parsed before processing. `:fuzzy`
   defaults to true and enables EOL/BOM/Unicode-tolerant matching when
   no exact match is found. `:atomic` defaults to true and means any
   validation failure returns an error without writing or backing up."
  [:map
   [:path [:string {:min 1}]]
   [:edits [:or
            [:vector [:map
                      [:old-text [:string {:min 1}]]
                      [:new-text :string]
                      [:replace-all {:optional true} :boolean]
                      [:expected-occurrences {:optional true} :int]]]
            :string]]
   [:fuzzy {:optional true} :boolean]
   [:atomic {:optional true} :boolean]
   [:expected-sha256 {:optional true} :string]
   [:force {:optional true} :boolean]
   [:clj-override {:optional true} :boolean]])

;; ---------------------------------------------------------------------------
;; Private helpers (path containment + edit splicing)
;; ---------------------------------------------------------------------------

(defn- write-dir-path
  "Build the normalized workspace-root `Path` used for containment checks,
   or nil when no workspace root is configured."
  [workspace-root]
  (when (seq workspace-root)
    (.normalize (.toPath (fpath/workspace-root->file workspace-root)))))

(defn- check-write-target
  "Validate a resolved write `path` against the registry/call options.

   Returns nil when the target is acceptable, or an error map (to be
   json-emitted by the caller) describing why it was refused:
     `:outside-write-dir` — path is not under `:workspace-root`
                              (skipped when `:force` is true)
     `:blocked-path`       — a path segment is in `:blocked-paths`
                              (never skippable)
     `:use-clj-edit`       — the target is a Clojure/EDN source file and
                              `:refuse-clojure?` is true without a
                              per-call `:clj-override`."
  [workspace-root ^Path path {:keys [force clj-override refuse-clojure? blocked-paths]}]
  (let [write-dir (some-> (write-dir-path workspace-root) fs/canonical-path)
        canonical (fs/canonical-path path)]
    (cond
      (and (not force) (not (fs/within-write-dir? write-dir canonical)))
      {:error :outside-write-dir}

      (or (fs/blocked-path? path blocked-paths)
          (fs/blocked-path? canonical blocked-paths))
      {:error :blocked-path}

      (and refuse-clojure? (not clj-override) (fs/clojure-file? path))
      {:error :use-clj-edit :use-tool "clojure_*"}

      :else nil)))

(defn- restore-eol
  "Re-apply the original file's EOL style and BOM to `s`.

  `eol-style` is `:crlf` or `:lf` (from [[fs/normalize-for-match]]). When
  `:crlf`, lone LF line endings are converted to CRLF. When `bom?` is
  true a leading U+FEFF is prepended — but only if `s` does not already
  start with one, so editing a BOM file (whose `apply-edits` result
  retains the original leading BOM) does not double-prepend it."
  [s eol-style bom?]
  (let [crlf (if (= eol-style :crlf)
               (-> s (str/replace "\r\n" "\n") (str/replace "\n" "\r\n"))
               s)]
    (if (and bom? (not (str/starts-with? crlf (str (char 0xFEFF)))))
      (str (char 0xFEFF) crlf)
      crlf)))

(defn- apply-edits
  "Splice the replacement `spans` into `original`.

  Each span is `{:start :end :text}`. Spans are applied in descending
  `:start` order so earlier offsets stay valid as later ones are
  replaced. Returns the edited string."
  [original spans]
  (loop [spans (sort-by :start > spans) acc original]
    (if (empty? spans)
      acc
      (let [{:keys [start end text]} (first spans)]
        (recur (rest spans)
               (str (subs acc 0 start) text (subs acc end)))))))

(defn- validate-edits
  "Validate every edit against `original` and collect replacement spans.

  Returns either `{:error k ...}` (with context keys) or
  `{:spans [{:start :end :text :fuzzy?}] :fuzzy-fired? bool}`.

  Per-edit checks: blank `:old-text`, no-op (`old` == `new`), and
  line-number-prefixed pastes. Matching uses [[fs/find-matches]] with
  `fuzzy?`. Count dispatch: zero matches -> `:no-match`, more than one
  match without `:replace-all` -> `:ambiguous-match`, and an
  `:expected-occurrences` mismatch -> `:count-mismatch`. Finally all
  collected spans are checked for overlap -> `:overlap`."
  [original edits fuzzy?]
  (let [fired-vol    (volatile! false)
        span-acc     (volatile! [])
        err          (loop [es edits]
                        (if (empty? es)
                          nil
                          (let [{:keys [old-text new-text replace-all expected-occurrences]}
                                (first es)]
                            (cond
                              (str/blank? old-text)
                              {:error :empty-old-text}

                              (= old-text new-text)
                              {:error :no-op}

                              (fs/scan-line-number-prefixes old-text)
                              {:error :line-number-prefix}

                              :else
                              (let [{:keys [matches fuzzy-fired?]}
                                    (fs/find-matches original old-text fuzzy?)]
                                (vswap! fired-vol #(or % fuzzy-fired?))
                                (cond
                                  (empty? matches)
                                  {:error :no-match}

                                  (and (> (count matches) 1) (not replace-all))
                                  {:error :ambiguous-match :count (count matches)}

                                  (and expected-occurrences
                                       (not= expected-occurrences (count matches)))
                                  {:error :count-mismatch
                                   :expected expected-occurrences
                                   :actual   (count matches)}

                                  :else
                                  (let [chosen (if replace-all matches [(first matches)])]
                                    (vswap! span-acc
                                            into
                                            (mapv (fn [m]
                                                    {:start  (:start m)
                                                     :end    (:end m)
                                                     :text   new-text
                                                     :fuzzy? (:fuzzy? m)})
                                                  chosen))
                                    (recur (rest es)))))))))]
    (if err
      err
      (let [spans  @span-acc
            sorted (sort-by :start spans)
            overlap (loop [prev-end -1 s sorted]
                      (if (empty? s)
                        nil
                        (let [cur (first s)]
                          (if (< (:start cur) prev-end)
                            {:error :overlap}
                            (recur (:end cur) (rest s))))))]
        (if overlap overlap {:spans (vec sorted) :fuzzy-fired? @fired-vol})))))

(defn- error-result
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

;; ---------------------------------------------------------------------------
;; Deftypes
;; ---------------------------------------------------------------------------

(defn- note-mutation!
  [idx {:keys [path tool sha256-before sha256-after content]}]
  (when idx
    (file-index/record-mutation! idx {:path path
                                      :tool tool
                                      :sha256-before sha256-before
                                      :sha256-after sha256-after
                                      :content content})))

(deftype WriteFileTool [workspace-root max-write-bytes refuse-clojure? blocked-paths clojure-guard? file-index]
  tool/Tool
 (-name [_] "file_write")
  (-description [_]
    "Overwrite a UTF-8 text file with the given content. The target path must be inside the configured workspace root (unless `force` is set) and must not touch blocked segments such as `.git`, `target`, or `node_modules`. Clojure/EDN source files are refused by default unless `clj-override` is set. Before mutating, a timestamped `.bak.<millis>` sidecar backup of the original is written and the new content is landed via an atomic temp-file + move. Optional `expected-sha256` aborts when the on-disk file no longer matches the caller's last-known digest. The write is size-capped by the registry's `:max-write-bytes`.")
  (-input-schema [_] InputSchema:WriteFile)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args _ctx]
    (try
      (let [path (fpath/resolve-path workspace-root (:path args))
            opts {:force          (:force args)
                  :clj-override   (:clj-override args)
                  :refuse-clojure? refuse-clojure?
                  :blocked-paths  blocked-paths}
            target-err (check-write-target workspace-root path opts)]
        (if target-err
          (json/generate-string target-err)
          (let [content  (:content args)
                expected (:expected-sha256 args)
                pre-err  (or (when (fs/scan-omission-placeholders content)
                               {:error :omission-placeholder})
                             (when (> (count (.getBytes content "UTF-8")) max-write-bytes)
                               {:error :file-too-large :limit max-write-bytes})
                             (when (and clojure-guard?
                                        (fs/clojure-file? path)
                                        (not (:ok (fs/clojure-round-trip? content))))
                               {:error :clojure-round-trip-failed}))]
            (if pre-err
              (json/generate-string pre-err)
              (fs/with-path-lock (fpath/path->str path)
                (let [existed (.exists (.toFile path))
                      cur     (try (Files/readAllBytes path)
                                   (catch Throwable _ nil))]
                  (if (and (:create-only args) existed)
                    (json/generate-string {:error :file-exists
                                           :path (fpath/path->str path)})
                    (if (and expected
                             (or (nil? cur)
                                 (not= expected (fs/sha256 cur))))
                      (json/generate-string
                       {:error :stale-file
                        :expected-sha256 expected
                        :actual-sha256 (when cur (fs/sha256 cur))})
                    (let [backup (fs/make-backup! path)]
                      (when (:create-dirs args)
                        (let [parent (.getParentFile (.toFile path))]
                          (when parent (.mkdirs parent))))
                      (fs/write-atomically! path content)
                      (let [written     (try (Files/readAllBytes path)
                                             (catch Throwable _ nil))
                            written-str (when written
                                          (String. ^bytes written StandardCharsets/UTF_8))]
                        (if (not= written-str content)
                          (json/generate-string {:error :write-verify-failed})
                          (let [digest (when written (fs/sha256 written))
                                prior  (when cur (fs/sha256 cur))]
                            (note-mutation! file-index
                                            {:path (fpath/path->str path)
                                             :tool "file_write"
                                             :sha256-before prior
                                             :sha256-after digest
                                             :content content})
                            (json/generate-string
                             {:path          (fpath/path->str path)
                              :bytes-written (count (.getBytes content "UTF-8"))
                              :backup-path   backup
                              :created       (not existed)
                              :changed       true
                              :sha256        digest
                              :previous-sha256 prior})))))))))))))
      (catch Throwable t
        (error-result t)))))

(deftype UpdateFileTool [workspace-root max-write-bytes refuse-clojure? blocked-paths clojure-guard? file-index]
  tool/Tool
 (-name [_] "file_update")
  (-description [_]
   "Apply in-place text edits to an existing UTF-8 file. Each edit replaces occurrences of `old-text` with `new-text`; `replace-all` swaps every occurrence, otherwise the edit must match exactly once. `expected-occurrences` asserts a known count. `fuzzy` (default true) falls back to EOL/BOM/Unicode-tolerant matching when no exact match exists. `atomic` (default true) means any validation failure returns an error without writing or backing up. The same containment, blocked-path, and Clojure-file guards as `file_write` apply, and a timestamped `.bak.<millis>` backup plus an atomic write protect the file on disk. Edits to the same path are serialized across threads.")
  (-input-schema [_] InputSchema:UpdateFile)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args _ctx]
    (try
      (let [path (fpath/resolve-path workspace-root (:path args))
            opts {:force          (:force args)
                  :clj-override   (:clj-override args)
                  :refuse-clojure? refuse-clojure?
                  :blocked-paths  blocked-paths}
            target-err (check-write-target workspace-root path opts)]
        (if target-err
          (json/generate-string target-err)
          (let [f (.toFile path)]
            (if (not (.exists f))
              (json/generate-string {:error :file-not-found})
              (let [edits-raw (:edits args)
                    edits (cond
                            (vector? edits-raw) edits-raw
                            (string? edits-raw)
                            (or (try (doall (json/parse-string edits-raw true))
                                     (catch Throwable _ nil))
                                (try (edn/read-string edits-raw)
                                     (catch Throwable _ nil)))
                            :else nil)]
                (if (nil? edits)
                  (json/generate-string {:error :edits-parse-failed})
                  (let [cur-bytes (Files/readAllBytes path)]
                    (if (> (count cur-bytes) max-write-bytes)
                      (json/generate-string {:error :file-too-large :limit max-write-bytes})
                      (let [sentinel (fs/staleness-sentinel path cur-bytes)
                            expected (:expected-sha256 args)]
                        (if (and expected (not= expected (:sha256 sentinel)))
                          (json/generate-string {:error :stale-file})
                          (let [original  (String. ^bytes cur-bytes StandardCharsets/UTF_8)
                                fuzzy?    (boolean (:fuzzy args true))
                                edit-out  (validate-edits original edits fuzzy?)]
                            (if (:error edit-out)
                              (json/generate-string edit-out)
                              (let [{:keys [eol-style bom?]}
                                    (fs/normalize-for-match original)
                                    result    (apply-edits original (:spans edit-out))
                                    result-eol (restore-eol result eol-style bom?)
                                    post-err  (or (when (and clojure-guard?
                                                              (fs/clojure-file? path)
                                                              (not (:ok (fs/clojure-round-trip? result-eol))))
                                                    {:error :clojure-round-trip-failed})
                                                  (when (> (count (.getBytes result-eol "UTF-8"))
                                                           max-write-bytes)
                                                    {:error :file-too-large :limit max-write-bytes}))]
                                (if post-err
                                  (json/generate-string post-err)
                                  (fs/with-path-lock (fpath/path->str path)
                                    (if (fs/check-staleness path sentinel)
                                      (json/generate-string {:error :stale-file})
                                      (let [backup (fs/make-backup! path)]
                                        (fs/write-atomically! path result-eol)
                                        (let [written (try (Files/readAllBytes path)
                                                           (catch Throwable _ nil))
                                              written-str (when written
                                                            (String. ^bytes written
                                                                     StandardCharsets/UTF_8))]
                                          (if (not= written-str result-eol)
                                            (json/generate-string {:error :write-verify-failed})
                                            (let [digest (when written (fs/sha256 written))]
                                              (note-mutation! file-index
                                                              {:path (fpath/path->str path)
                                                               :tool "file_update"
                                                               :sha256-before (:sha256 sentinel)
                                                               :sha256-after digest
                                                               :content result-eol})
                                              (json/generate-string
                                               {:path          (fpath/path->str path)
                                                :bytes-written (count (.getBytes result-eol "UTF-8"))
                                                :backup-path   backup
                                                :edits-applied (count (:spans edit-out))
                                                :fuzzy-fired   (:fuzzy-fired? edit-out)
                                                :changed       true
                                                :sha256        digest
                                                :previous-sha256 (:sha256 sentinel)})))))))))))))))))))))
      (catch Throwable t
        (error-result t)))))

;; ---------------------------------------------------------------------------
;; Public factory fns
;; ---------------------------------------------------------------------------

(defn write-file
 "Return a new `file_write` Tool instance.

  `opts` may provide `:max-write-bytes` (default
  [[fs/default-max-write-bytes]]), `:refuse-clojure?` (default true),
  `:blocked-paths` (default [[fs/default-blocked-paths]]), and
  `:clojure-guard?` (default false, enables rewrite-clj round-trip
  validation of written Clojure/EDN source), and optional `:file-index`
  (advisory `FileIndex` written after a verified commit).")
  ([] (write-file nil {}))
  ([workspace-root] (write-file workspace-root {}))
  ([workspace-root {:keys [max-write-bytes refuse-clojure? blocked-paths clojure-guard? file-index]}]
   (->WriteFileTool workspace-root
                    (or max-write-bytes fs/default-max-write-bytes)
                    (if (some? refuse-clojure?) refuse-clojure? true)
                    (or blocked-paths fs/default-blocked-paths)
                    (boolean clojure-guard?)
                    file-index)))

(defn update-file
 "Return a new `file_update` Tool instance.

  `opts` accepts the same keys as [[write-file]]."
  ([] (update-file nil {}))
  ([workspace-root] (update-file workspace-root {}))
  ([workspace-root {:keys [max-write-bytes refuse-clojure? blocked-paths clojure-guard? file-index]}]
   (->UpdateFileTool workspace-root
                     (or max-write-bytes fs/default-max-write-bytes)
                     (if (some? refuse-clojure?) refuse-clojure? true)
                     (or blocked-paths fs/default-blocked-paths)
                     (boolean clojure-guard?)
                     file-index)))
