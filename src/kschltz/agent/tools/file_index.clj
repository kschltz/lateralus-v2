(ns kschltz.agent.tools.file-index
  "Model-facing file index tools: `file_reindex` and `file_edits`.

   Only registered when a FileIndex is configured. They never bypass
   workspace containment or blocked-path checks."
  (:require [cheshire.core :as json]
            [kschltz.agent.store.file-index :as index]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.file-path :as fpath]
            [kschltz.agent.tools.file-read-policy :as read-policy]
            [kschltz.agent.tools.file-safety :as fs]))

(def InputSchema:Reindex
  [:map
   [:path {:optional true} :string]
   [:max-file-bytes {:optional true} :int]])

(def InputSchema:Edits
  [:map
   [:path {:optional true} :string]
   [:limit {:optional true} :int]])

(deftype ReindexTool [idx workspace-root blocked-paths
                      allow-read-outside-workspace? default-max-file-bytes]
  tool/Tool
  (-name [_] "file_reindex")
  (-description [_]
    "Rebuild the workspace file index under `path` (default workspace root). Skips blocked trees and files larger than `max-file-bytes`. The filesystem remains source of truth; this only refreshes the advisory index used by `file_search` and `file_edits`.")
  (-input-schema [_] InputSchema:Reindex)
  (-output-schema [_] :string)
  (-invoke [_ args _ctx]
    (try
      (let [root (read-policy/resolve-readable-path
                  workspace-root
                  (or (:path args) ".")
                  blocked-paths
                  allow-read-outside-workspace?)
            max-bytes (or (:max-file-bytes args) default-max-file-bytes)
            stats (index/reindex-tree! idx root blocked-paths max-bytes)]
        (json/generate-string (assoc stats :ok true :path (fpath/path->str root))))
      (catch Throwable t
        (read-policy/error-result t)))))

(deftype EditsTool [idx workspace-root blocked-paths allow-read-outside-workspace?]
  tool/Tool
  (-name [_] "file_edits")
  (-description [_]
    "List recent file-index edit records for `path` (or the whole workspace). Each row has path, tool, before/after SHA-256, optional line range, and timestamp. Advisory audit log only.")
  (-input-schema [_] InputSchema:Edits)
  (-output-schema [_] :string)
  (-invoke [_ args _ctx]
    (try
      (when (:path args)
        (read-policy/resolve-readable-path
         workspace-root
         (:path args)
         blocked-paths
         allow-read-outside-workspace?))
      (let [path (when (:path args)
                   (fpath/path->str
                    (fpath/resolve-path workspace-root (:path args))))
            rows (index/-edits idx {:path path
                                    :limit (or (:limit args) 50)})]
        (json/generate-string {:edits rows :count (count rows)}))
      (catch Throwable t
        (read-policy/error-result t)))))

(defn reindex-tool
  [idx workspace-root opts]
  (->ReindexTool idx workspace-root
                 (or (:blocked-paths opts) fs/default-blocked-paths)
                 (boolean (:allow-read-outside-workspace? opts))
                 (or (:max-search-file-bytes opts)
                     index/default-max-content-bytes)))

(defn edits-tool
  [idx workspace-root opts]
  (->EditsTool idx workspace-root
               (or (:blocked-paths opts) fs/default-blocked-paths)
               (boolean (:allow-read-outside-workspace? opts))))

(defn index-tools
  "Return file_reindex + file_edits when `idx` is a FileIndex, else {}."
  [idx workspace-root opts]
  (if (index/file-index? idx)
    {"file_reindex" (reindex-tool idx workspace-root opts)
     "file_edits" (edits-tool idx workspace-root opts)}
    {}))
