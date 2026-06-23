(ns kschltz.agent.tools.runtime.tools
  "Tool records for the lateralus runtime-eval suite.

   Three `Tool` implementations let the agent prototype in Clojure by
   actually running code, and pull in dependencies on the fly:

     - `EvalTool`        — `clojure/eval`
     - `AddLibTool`      — `clojure/add-lib`
     - `LoadedLibsTool`  — `clojure/loaded-libs`

   Each tool holds a `ClojureRuntime` (default: the in-process JVM
   runtime) plus the merged config map and dispatches through the
   `ClojureRuntime` protocol. Every `-invoke` is wrapped in a try/catch
   that emits a JSON envelope on failure so the model always sees a
   structured result.

   Safety toggles live in the config: `:enabled?` (master switch, default
   true) and `:network?` (gates `clojure/add-lib`, default true).
   `clojure/eval` runs arbitrary Clojure in-process — operators who want
   an air-gapped, read-only agent should set `:enabled? false`."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.runtime.jvm :as jvm]
            [kschltz.agent.tools.runtime.protocol :as proto]
            [kschltz.agent.tools.runtime.schemas :as schemas]))

;; ---------------------------------------------------------------------------
;; Envelopes
;; ---------------------------------------------------------------------------

(defn- json-envelope
  "JSON-serialize `m` (pretty) for return to the model."
  [m]
  (json/generate-string m {:pretty true}))

(defn- error-envelope
  "Build a JSON error envelope from a Throwable."
  [^Throwable t]
  (json-envelope {:error (ex-message t)
                  :phase "tool"}))

(defn- disabled-envelope
  "Returned when the runtime-eval suite is disabled via `:enabled? false`."
  [op]
  (json-envelope {:error (str op " is disabled (runtime config :enabled? is false)")
                  :phase "disabled"}))

(defn- network-disabled-envelope
  "Returned when `clojure/add-lib` is invoked but `:network?` is false."
  []
  (json-envelope {:error "runtime dependency loading is disabled (runtime config :network? is false)"
                  :phase "network-disabled"
                  :added []}))

(defn- enabled?
  [config]
  (get config :enabled? true))

(defn- network-allowed?
  [config]
  (get config :network? true))

;; ---------------------------------------------------------------------------
;; Coordinate parsing for clojure/add-lib
;; ---------------------------------------------------------------------------

(defn- coerce-coord-keys
  "EDN-decoded coordinate maps from the model may carry string keys for
   the lib name and string/keyword keys inside the coordinate map. Coerce
   the outer keys to symbols and pass the inner maps through unchanged so
   `clojure.repl.deps/add-libs` receives the shape it expects."
  [m]
  (into {}
        (map (fn [[k v]]
               [(if (symbol? k) k (symbol (name k))) v]))
        m))

(defn parse-coords
  "Turn `clojure/add-lib` args into a coords map of lib-symbol ->
   coordinate-map. Prefers an explicit `:coords` EDN string; otherwise
   builds `{lib {:mvn/version version}}` from `:lib` (+ optional
   `:version`, defaulting to \"RELEASE\"). Throws ex-info on bad input."
  [{:keys [lib version coords]}]
  (cond
    (and (string? coords) (seq coords))
    (let [parsed (edn/read-string coords)]
      (if (map? parsed)
        (coerce-coord-keys parsed)
        (throw (ex-info "`coords` must be an EDN map of lib -> coordinate map"
                        {:coords coords}))))

    (and (string? lib) (seq lib))
    {(symbol lib) {:mvn/version (or (not-empty version) "RELEASE")}}

    :else
    (throw (ex-info "clojure/add-lib requires `lib` (+ optional `version`) or `coords`"
                    {}))))

;; ---------------------------------------------------------------------------
;; Tool records
;; ---------------------------------------------------------------------------

(deftype EvalTool [runtime config]
  tool/Tool
  (-name [_] "clojure/eval")
  (-description [_]
    "Evaluate Clojure code in a persistent in-process runtime namespace and return the result. The `code` string may contain multiple top-level forms; `def`s and `require`s persist across calls so you can build up state incrementally while prototyping. Returns JSON with `ns`, `forms` (count evaluated), `value` (pr-str of the last form), `output` (captured stdout), and `error` (formatted exception or null). Optional `ns` selects the target namespace. Evaluation is time-limited; runaway loops are cancelled.")
  (-input-schema [_] schemas/EvalInput)
  (-output-schema [_] schemas/OutputString)
  (-invoke [_ args _ctx]
    (try
      (if-not (enabled? config)
        (disabled-envelope "clojure/eval")
        (json-envelope
         (proto/-eval runtime (:code args)
                      (cond-> {} (:ns args) (assoc :ns (:ns args))))))
      (catch Throwable t
        (error-envelope t)))))

(deftype AddLibTool [runtime config]
  tool/Tool
  (-name [_] "clojure/add-lib")
  (-description [_]
    "Add a Maven (or Git) dependency to the running JVM at runtime, without restarting, using Clojure 1.12's runtime dependency loading. Provide `lib` (e.g. \"org.clojure/data.json\") with an optional `version` (defaults to the latest RELEASE), or pass `coords`: an EDN map string of lib -> coordinate map for advanced/git coordinates. After it returns, `require` the newly added namespaces via clojure/eval. Returns JSON with `added` (libs loaded) and `error` (null on success). Requires the agent to run under the Clojure CLI.")
  (-input-schema [_] schemas/AddLibInput)
  (-output-schema [_] schemas/OutputString)
  (-invoke [_ args _ctx]
    (try
      (cond
        (not (enabled? config))         (disabled-envelope "clojure/add-lib")
        (not (network-allowed? config)) (network-disabled-envelope)
        :else
        (let [coords (parse-coords args)]
          (json-envelope (proto/-add-libs runtime coords {}))))
      (catch Throwable t
        (error-envelope t)))))

(deftype LoadedLibsTool [runtime config]
  tool/Tool
  (-name [_] "clojure/loaded-libs")
  (-description [_]
    "List the Clojure libs currently loaded in the running JVM. No arguments. Returns JSON with a `libs` array. Useful for checking whether a dependency added via clojure/add-lib is available before requiring it.")
  (-input-schema [_] schemas/LoadedLibsInput)
  (-output-schema [_] schemas/OutputString)
  (-invoke [_ _args _ctx]
    (try
      (if-not (enabled? config)
        (disabled-envelope "clojure/loaded-libs")
        (json-envelope {:libs (proto/-loaded-libs runtime)}))
      (catch Throwable t
        (error-envelope t)))))

;; ---------------------------------------------------------------------------
;; Registry factory
;; ---------------------------------------------------------------------------

(defn runtime-registry
  "Build the 3-tool registry for the runtime-eval suite.

   `config` is a `RuntimeConfig` map (see
   `kschltz.agent.tools.runtime.schemas/RuntimeConfig`). When it carries
   a `:runtime` key holding a `ClojureRuntime` instance (the test seam),
   that runtime is used; otherwise an in-process `JvmRuntime` is built
   from the config.

   Returns:
     {\"clojure/eval\"        EvalTool
      \"clojure/add-lib\"     AddLibTool
      \"clojure/loaded-libs\" LoadedLibsTool}"
  ([] (runtime-registry {}))
  ([config]
   (let [config (or config {})
         rt     (let [r (:runtime config)]
                  (if (proto/capabilities? r)
                    r
                    (jvm/jvm-runtime (dissoc config :runtime))))]
     {"clojure/eval"        (->EvalTool rt config)
      "clojure/add-lib"     (->AddLibTool rt config)
      "clojure/loaded-libs" (->LoadedLibsTool rt config)})))
