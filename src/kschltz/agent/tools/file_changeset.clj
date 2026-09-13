(ns kschltz.agent.tools.file-changeset
  "Validated, transactional multi-file changes for agent-authored work."
  (:require [cheshire.core :as json]
            [kschltz.agent.store.file-index :as file-index]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.file-path :as fpath]
            [kschltz.agent.tools.file-safety :as fs])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]))

(def Operation
  [:multi {:dispatch :op}
   [:create
    [:map {:closed true}
     [:op [:= :create]]
     [:path [:string {:min 1}]]
     [:content :string]]]
   [:replace
    [:map {:closed true}
     [:op [:= :replace]]
     [:path [:string {:min 1}]]
     [:content :string]
     [:expected-sha256 {:optional true} [:string {:min 64 :max 64}]]]]
   [:delete
    [:map {:closed true}
     [:op [:= :delete]]
     [:path [:string {:min 1}]]
     [:expected-sha256 {:optional true} [:string {:min 64 :max 64}]]]]])

(def InputSchema
  [:map {:closed true}
   [:changes [:vector {:min 1} Operation]]
   [:dry-run {:optional true} :boolean]])

(defn- path-exists?
  [^Path path]
  (Files/exists path (make-array java.nio.file.LinkOption 0)))

(defn- resolve-target
  [workspace-root user-path blocked-paths]
  (let [requested (fpath/resolve-path workspace-root user-path)
        canonical (fs/canonical-path requested)
        root (fs/canonical-path
              (.toPath (fpath/workspace-root->file workspace-root)))]
    (cond
      (not (fs/within-write-dir? root canonical))
      (throw (ex-info "Change-set target resolves outside the configured workspace"
                      {:error :outside-workspace :path user-path}))

      (or (fs/blocked-path? requested blocked-paths)
          (fs/blocked-path? canonical blocked-paths))
      (throw (ex-info "Change-set target contains a blocked segment"
                      {:error :blocked-path :path user-path}))

      :else canonical)))

(defn- validate-content!
  [^Path target content max-write-bytes]
  (let [bytes (.getBytes ^String content StandardCharsets/UTF_8)]
    (when (> (count bytes) max-write-bytes)
      (throw (ex-info "Change-set content exceeds the configured size limit"
                      {:error :file-too-large
                       :path (str target)
                       :limit max-write-bytes})))
    (when-let [placeholder (fs/scan-omission-placeholders content)]
      (throw (ex-info "Change-set content contains an omission placeholder"
                      {:error :omission-placeholder
                       :path (str target)
                       :placeholder placeholder})))
    ;; Unlike file_write, this tool intentionally creates Clojure files. The
    ;; stronger invariant is that every Clojure/EDN result must parse.
    (when (fs/clojure-file? target)
      (let [result (fs/clojure-round-trip? content)]
        (when-not (:ok result)
          (throw (ex-info "Change-set contains invalid Clojure/EDN source"
                          {:error :clojure-round-trip-failed
                           :path (str target)
                           :reason (:reason result)})))))
    bytes))

(defn- validate-change
  [workspace-root blocked-paths max-write-bytes change]
  (let [target (resolve-target workspace-root (:path change) blocked-paths)
        exists? (path-exists? target)
        op (:op change)]
    (when (and exists? (not (Files/isRegularFile target
                                                (make-array java.nio.file.LinkOption 0))))
      (throw (ex-info "Change-set target is not a regular file"
                      {:error :not-regular-file :path (:path change)})))
    (case op
      :create
      (when exists?
        (throw (ex-info "Change-set create target already exists"
                        {:error :already-exists :path (:path change)})))

      (:replace :delete)
      (when-not exists?
        (throw (ex-info "Change-set target does not exist"
                        {:error :file-not-found :path (:path change)})))

      (throw (ex-info "Unsupported change-set operation"
                      {:error :unsupported-operation :op op})))
    (let [before-bytes (when exists? (Files/readAllBytes target))
          before-sha (when before-bytes (fs/sha256 before-bytes))
          expected (:expected-sha256 change)]
      (when (and expected (not= expected before-sha))
        (throw (ex-info "Change-set is based on a stale file snapshot"
                        {:error :stale-file
                         :path (:path change)
                         :expected-sha256 expected
                         :actual-sha256 before-sha})))
      (let [after-bytes (when-not (= op :delete)
                          (validate-content! target (:content change)
                                             max-write-bytes))]
        (when (and (= op :replace)
                   (java.util.Arrays/equals ^bytes before-bytes ^bytes after-bytes))
          (throw (ex-info "Change-set replacement would not change the file"
                          {:error :no-op :path (:path change)})))
        (assoc change
               :target target
               :target-str (fpath/path->str target)
               :existed? exists?
               :before-bytes before-bytes
               :before-sha256 before-sha
               :after-bytes after-bytes
               :after-sha256 (when after-bytes (fs/sha256 after-bytes)))))))

(defn- with-path-locks
  [path-strs f]
  (if-let [path-str (first path-strs)]
    (fs/with-path-lock path-str
      (with-path-locks (next path-strs) f))
    (f)))

(defn- restore-change!
  [{:keys [^Path target existed? before-bytes]}]
  (if existed?
    (do
      (when-let [parent (.getParent target)]
        (Files/createDirectories parent
                                 (make-array java.nio.file.attribute.FileAttribute 0)))
      (fs/write-atomically! target
                            (String. ^bytes before-bytes StandardCharsets/UTF_8)))
    (when (path-exists? target)
      (Files/delete target))))

(defn- commit-change!
  [{:keys [op ^Path target content after-bytes]}]
  (case op
    (:create :replace)
    (do
      (when-let [parent (.getParent target)]
        (Files/createDirectories parent
                                 (make-array java.nio.file.attribute.FileAttribute 0)))
      (fs/write-atomically! target content)
      (let [written (Files/readAllBytes target)]
        (when-not (java.util.Arrays/equals ^bytes after-bytes ^bytes written)
          (throw (ex-info "Change-set write verification failed"
                          {:error :write-verify-failed :path (str target)})))))

    :delete
    (Files/delete target)))

(defn- public-change
  [{:keys [op path before-sha256 after-sha256]}]
  {:op op
   :path path
   :previous-sha256 before-sha256
   :sha256 after-sha256})

(defn- record-index!
  [idx change]
  (when idx
    (file-index/record-mutation!
     idx
     {:path (:target-str change)
      :tool "file_change_set"
      :sha256-before (:before-sha256 change)
      :sha256-after (:after-sha256 change)
      :content (when-not (= :delete (:op change)) (:content change))})))

(defn- apply-change-set!
  [workspace-root blocked-paths max-write-bytes changes dry-run? file-index]
  (let [targets (mapv #(resolve-target workspace-root (:path %) blocked-paths)
                      changes)
        target-strs (mapv fpath/path->str targets)]
    (when-not (= (count target-strs) (count (distinct target-strs)))
      (throw (ex-info "A change-set may contain only one operation per path"
                      {:error :duplicate-path})))
    (with-path-locks
      (sort target-strs)
      (fn []
        ;; Validation occurs under all target locks and before the first write.
        (let [validated (mapv #(validate-change workspace-root blocked-paths
                                                max-write-bytes %)
                              changes)]
          (if dry-run?
            {:ok true
             :dry-run true
             :changed false
             :rollback :not-needed
             :changes (mapv public-change validated)}
            (let [committed (atom [])]
              (try
                (doseq [change validated]
                  (commit-change! change)
                  (swap! committed conj change))
                (doseq [change validated]
                  (record-index! file-index change))
                {:ok true
                 :dry-run false
                 :changed true
                 :rollback :not-needed
                 :changes (mapv public-change validated)}
                (catch Throwable commit-error
                  (let [rollback-errors
                        (reduce
                         (fn [errors change]
                           (try
                             (restore-change! change)
                             errors
                             (catch Throwable rollback-error
                               (conj errors {:path (:path change)
                                             :message (ex-message rollback-error)}))))
                         []
                         (reverse @committed))]
                    (throw
                     (ex-info "Change-set commit failed and rollback was attempted"
                              {:error :transaction-failed
                               :cause-error (or (:error (ex-data commit-error))
                                                :filesystem-error)
                               :rollback (if (empty? rollback-errors)
                                           :completed
                                           :incomplete)
                               :rollback-errors rollback-errors}
                              commit-error))))))))))))

(defn- error-result
  [t]
  (let [data (if (instance? clojure.lang.ExceptionInfo t) (ex-data t) {})]
    (json/generate-string
     (merge {:ok false
             :error (or (:error data) :filesystem-error)
             :message (or (ex-message t) (.getName (class t)))}
            (dissoc data :error)))))

(deftype FileChangeSetTool [workspace-root blocked-paths max-write-bytes file-index]
  tool/Tool
  (-name [_] "file_change_set")
  (-description [_]
    "Validate and atomically apply a multi-file transaction. Supports create, replace, and delete, plus dry-run. Every path is locked in sorted order; every change is validated before mutation; failed commits roll back prior writes. New Clojure/EDN files are allowed only when they parse successfully.")
  (-input-schema [_] InputSchema)
  (-output-schema [_] :string)
  (-invoke [_ args _ctx]
    (try
      (json/generate-string
       (apply-change-set! workspace-root blocked-paths max-write-bytes
                          (:changes args) (boolean (:dry-run args)) file-index))
      (catch Throwable t
        (error-result t)))))

(defn file-change-set
  ([]
   (file-change-set nil {}))
  ([workspace-root]
   (file-change-set workspace-root {}))
  ([workspace-root {:keys [blocked-paths max-write-bytes file-index]}]
   (->FileChangeSetTool workspace-root
                        (or blocked-paths fs/default-blocked-paths)
                        (or max-write-bytes fs/default-max-write-bytes)
                        file-index)))
