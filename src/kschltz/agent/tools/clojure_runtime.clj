(ns kschltz.agent.tools.clojure-runtime
  "In-process Clojure prototyping tools for the lateralus agent loop.

   These tools let the model evaluate Clojure code in a persistent
   per-session REPL environment and dynamically load dependencies at
   runtime using Clojure 1.12's `clojure.repl.deps` (`add-lib`,
   `add-libs`, `sync-deps`).

   Use `clojure/*` structured-edit tools for changes that should persist
   on disk; use these runtime tools for quick experiments and
   prototyping."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.clojure-runtime.impl :as impl]
            [kschltz.agent.tools.clojure-runtime.protocol :as protocol]))

(def ^:private OutputSchema:String :string)

(defn- session-id-from-ctx [ctx]
  (or (:exchange/session-id ctx)
      (get-in ctx [:agent/state :agent/session-id])
      "default"))

(defn- ok-result [m]
  (json/generate-string m))

(defn- error-envelope [t]
  (json/generate-string
   (cond-> {:error (ex-message t)}
     (:phase (ex-data t)) (assoc :phase (:phase (ex-data t)))
     (:lib (ex-data t)) (assoc :lib (:lib (ex-data t)))
     (:ns (ex-data t)) (assoc :ns (:ns (ex-data t))))))

(defn- normalize-aliases [aliases]
  (when (seq aliases)
    (mapv (fn [a]
            (if (keyword? a) a (keyword (str/replace (str a) #"^:" ""))))
          aliases)))

(def InputSchema:Eval
  [:map
   [:code {:description "Clojure source to evaluate (one or more forms)"} :string]
   [:ns {:description "Optional namespace symbol to eval in (defaults to the session REPL ns)", :optional true}
    [:maybe :string]]])

(def InputSchema:AddLib
  [:map
   [:lib {:description "Library symbol as a string, e.g. \"org.clojure/data.json\""} :string]
   [:coord {:description "Optional coordinate map, e.g. {:mvn/version \"2.4.0\"}", :optional true}
    protocol/LibCoord]])

(def InputSchema:AddLibs
  [:map
   [:libs {:description "Map of lib string to coordinate map"}
    [:map-of :string protocol/LibCoord]]])

(def InputSchema:SyncDeps
  [:map
   [:aliases {:description "Optional deps.edn alias keywords to include", :optional true}
    [:vector :string]]
   [:deps-edn-path {:description "Optional path to deps.edn (defaults to configured path or project deps.edn)", :optional true}
    [:maybe :string]]])

(deftype EvalTool [runtime]
  tool/Tool
  (-name [_] "clojure/eval")
  (-description [_]
    "Evaluate Clojure code in a persistent per-session REPL environment.
     Returns JSON with :value (printed), :type, optional :stdout, :ns, and
     :forms-evaluated. Definitions persist across calls in the same session.
     For file changes that should persist, use clojure/* edit tools instead.")
  (-input-schema [_] InputSchema:Eval)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args ctx]
    (try
      (ok-result (protocol/-eval runtime
                               (cond-> {:session-id (session-id-from-ctx ctx)
                                        :code (:code args)}
                                 (:ns args) (assoc :ns (:ns args)))))
      (catch Throwable t (error-envelope t)))))

(deftype AddLibTool [runtime]
  tool/Tool
  (-name [_] "clojure/add-lib")
  (-description [_]
    "Dynamically add a Clojure library to the runtime classpath using
     Clojure 1.12 add-lib. Downloads from Maven if needed. Already-loaded
     libs are not updated. After loading, require the namespace and use
     clojure/eval as normal.")
  (-input-schema [_] InputSchema:AddLib)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args ctx]
    (try
      (ok-result (protocol/-add-lib runtime
                                  (cond-> {:session-id (session-id-from-ctx ctx)
                                           :lib (:lib args)}
                                    (:coord args) (assoc :coord (:coord args)))))
      (catch Throwable t (error-envelope t)))))

(deftype AddLibsTool [runtime]
  tool/Tool
  (-name [_] "clojure/add-libs")
  (-description [_]
    "Dynamically add multiple Clojure libraries together using Clojure 1.12
     add-libs. Resolves transitive dependencies jointly. Prefer this over
     repeated clojure/add-lib when loading several related libs.")
  (-input-schema [_] InputSchema:AddLibs)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args ctx]
    (try
      (ok-result (protocol/-add-libs runtime
                                    {:session-id (session-id-from-ctx ctx)
                                     :libs (:libs args)}))
      (catch Throwable t (error-envelope t)))))

(deftype SyncDepsTool [runtime]
  tool/Tool
  (-name [_] "clojure/sync-deps")
  (-description [_]
    "Sync libraries from deps.edn into the runtime classpath using Clojure 1.12
     sync-deps. Loads any deps from the file that are not yet on the classpath.
     Optionally pass :aliases to include alias deps (e.g. [\"test\" \"dev\"]).")
  (-input-schema [_] InputSchema:SyncDeps)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args ctx]
    (try
      (ok-result (protocol/-sync-deps runtime
                                      (cond-> {:session-id (session-id-from-ctx ctx)}
                                        (seq (:aliases args))
                                        (assoc :aliases (normalize-aliases (:aliases args)))
                                        (:deps-edn-path args)
                                        (assoc :deps-edn-path (:deps-edn-path args)))))
      (catch Throwable t (error-envelope t)))))

(deftype ReplResetTool [runtime]
  tool/Tool
  (-name [_] "clojure/repl-reset")
  (-description [_]
    "Reset the per-session Clojure REPL environment. Discards all definitions
     created via clojure/eval in this session. Does not remove libraries
     already added to the classpath.")
  (-input-schema [_] [:map {:closed true}])
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ _args ctx]
    (try
      (ok-result (protocol/-reset runtime
                                  {:session-id (session-id-from-ctx ctx)}))
      (catch Throwable t (error-envelope t)))))

(defn clojure-runtime-registry
  "Return a map of Clojure runtime tool name -> Tool instance.

   Accepts an optional `opts` map passed to `impl/runtime`:
     :enabled?       — default true (set false to disable eval/deps)
     :deps-edn-path  — default deps.edn path for sync-deps
     :max-code-bytes — max eval code size (default 65536)"
  ([] (clojure-runtime-registry {}))
  ([opts]
   (let [runtime (impl/runtime opts)]
     {"clojure/eval"       (->EvalTool runtime)
      "clojure/add-lib"    (->AddLibTool runtime)
      "clojure/add-libs"   (->AddLibsTool runtime)
      "clojure/sync-deps"  (->SyncDepsTool runtime)
      "clojure/repl-reset" (->ReplResetTool runtime)})))
