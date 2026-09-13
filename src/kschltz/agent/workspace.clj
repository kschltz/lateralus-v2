(ns kschltz.agent.workspace
  "Workspace-root resolution and filesystem-tool rebinding.

   File and Clojure tools capture `:workspace-root` at Integrant init.
   Runtime updates persist `:agent/workspace-root` on session state and
   rebind those tools through `:agent/tool-registry-transform`."
  (:require [clojure.java.io :as io]
            [kschltz.agent.tools.clojure :as tools.clojure]
            [kschltz.agent.tools.filesystem :as tools.filesystem]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def WorkspaceOpts
  [:map
   [:file-tools {:optional true} :map]
   [:clojure-tools {:optional true} :map]
   [:default-root {:optional true} :string]])

(defn normalize-root
  "Canonical absolute path string for `root`, or nil when blank."
  [root]
  (when (seq (str root))
    (-> (io/file (str root))
        .getCanonicalPath
        str)))

(defn validate-root
  "Return nil when `root` exists as a directory, otherwise an error string."
  [root]
  (let [norm (normalize-root root)]
    (cond
      (nil? norm) "workspace-root must be a non-empty path"
      (not (.exists (io/file norm))) (str "workspace-root does not exist: " norm)
      (not (.isDirectory (io/file norm))) (str "workspace-root is not a directory: " norm)
      :else nil)))

(defn effective-root
  "Session workspace root: durable state override, then configured default."
  [state default-root]
  (or (some-> (:agent/workspace-root state) normalize-root)
      (some-> default-root normalize-root)
      (normalize-root ".")))

(defn workspace-tool-names
  "Built-in tool names whose implementations are workspace-root bound."
  [opts]
  (into #{}
        (concat (keys (tools.filesystem/filesystem-registry
                        (or (:file-tools opts) {})))
                (keys (tools.clojure/clojure-registry
                        (or (:clojure-tools opts) {}))))))

(defn rebind-registry
  "Replace workspace-bound tools in `registry` for `root`."
  [registry opts root]
  (let [root' (normalize-root root)
        file-opts (assoc (or (:file-tools opts) {}) :workspace-root root')
        clj-opts (assoc (or (:clojure-tools opts) {}) :workspace-root root')
        rebound (merge (tools.filesystem/filesystem-registry file-opts)
                       (tools.clojure/clojure-registry clj-opts))
        names (workspace-tool-names opts)]
    (merge registry (select-keys rebound names))))

(defn transform-registry
  "Build a registry transform fn for `opts` and optional `default-root`."
  [opts default-root]
  (fn [registry state]
    (rebind-registry registry opts (effective-root state default-root))))

(m/=> normalize-root [:=> [:cat :any] [:maybe :string]])
(m/=> validate-root [:=> [:cat :any] [:maybe :string]])
(m/=> effective-root [:=> [:cat [:maybe :map] [:maybe :string]] :string])
(m/=> rebind-registry [:=> [:cat :map WorkspaceOpts :string] :map])

(defn instrument!
  []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.workspace)]}))

(instrument!)
