(ns kschltz.agent.tools.clojure-runtime.protocol
  "ClojureRuntime protocol for in-process Clojure prototyping.

   The runtime eval surface (`clojure/eval`, `clojure/add-lib`,
   `clojure/add-libs`, `clojure/sync-deps`, `clojure/repl-reset`)
   dispatches through this protocol. Implementations maintain a
   per-session evaluation namespace and support Clojure 1.12 dynamic
   dependency loading via `clojure.repl.deps`.

   Malli schemas in this namespace describe the protocol boundary.
   The implementation validates inputs and outputs at each call."
  (:require [malli.core :as m]))

(def LibCoord
  "Coordinate map for a single library (Maven, git, local, etc.)."
  [:map
   [:mvn/version {:optional true} :string]
   [:git/url {:optional true} :string]
   [:git/sha {:optional true} :string]
   [:git/tag {:optional true} :string]
   [:local/root {:optional true} :string]])

(def EvalInput
  "Input to `-eval`."
  [:map
   [:session-id :string]
   [:code :string]
   [:ns {:optional true} [:maybe :string]]])

(def EvalResult
  "Successful eval result."
  [:map
   [:value :string]
   [:type :string]
   [:stdout {:optional true} :string]
   [:ns :string]
   [:forms-evaluated :int]])

(def AddLibInput
  "Input to `-add-lib`."
  [:map
   [:session-id :string]
   [:lib :string]
   [:coord {:optional true} LibCoord]])

(def AddLibsInput
  "Input to `-add-libs`."
  [:map
   [:session-id :string]
   [:libs [:map-of :string LibCoord]]])

(def SyncDepsInput
  "Input to `-sync-deps`."
  [:map
   [:session-id :string]
   [:aliases {:optional true} [:vector :keyword]]
   [:deps-edn-path {:optional true} [:maybe :string]]])

(def DepsResult
  "Result from dependency-loading operations."
  [:map
   [:libs [:vector :string]]
   [:session-id :string]])

(def ResetInput
  "Input to `-reset`."
  [:map [:session-id :string]])

(def ResetResult
  "Result from `-reset`."
  [:map
   [:session-id :string]
   [:reset? :boolean]])

(defprotocol ClojureRuntime
  "In-process Clojure evaluation and dynamic dependency loading.

   Each method raises `ex-info` on failure with `:phase` set to one of:
     - `:disabled`   — runtime eval is turned off in config
     - `:validation` — Malli input validation failed
     - `:parse`      — code could not be read
     - `:eval`       — evaluation raised
     - `:deps`       — add-lib/add-libs/sync-deps failed"
  (-eval [runtime input]
    "Evaluate `code` in the session namespace. Returns a map
     satisfying `EvalResult`.")
  (-add-lib [runtime input]
    "Add a single library to the session classpath via
     `clojure.repl.deps/add-lib`. Returns a map satisfying
     `DepsResult`.")
  (-add-libs [runtime input]
    "Add multiple libraries together via `clojure.repl.deps/add-libs`.
     Returns a map satisfying `DepsResult`.")
  (-sync-deps [runtime input]
    "Sync libraries from deps.edn via `clojure.repl.deps/sync-deps`.
     Returns a map satisfying `DepsResult`.")
  (-reset [runtime input]
    "Discard the session eval namespace and start fresh. Returns a map
     satisfying `ResetResult`."))

(defn explain
  "Return Malli explanation errors for `schema` + `value`, or nil."
  [schema value]
  (some-> (m/explain schema value) :errors))
