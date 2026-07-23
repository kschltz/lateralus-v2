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
            [kschltz.agent.tools.runtime.paren-repair :as paren-repair]
            [kschltz.agent.tools.runtime.protocol :as proto]
            [kschltz.agent.tools.runtime.schemas :as schemas]))

;; ---------------------------------------------------------------------------
;; Envelopes
;; ---------------------------------------------------------------------------

(defn- json-envelope
  "JSON-serialize `m` (compact) for return to the model. Compact form —
   no pretty-print whitespace — so the model does not re-tokenize ~25%
   filler every turn (audit 2026-07 rec #6: the old `{:pretty true}` added
   whitespace the model paid for on every tool result)."
  [m]
  (json/generate-string m))

(defn- error-envelope
  "Build a JSON error envelope from a Throwable. Carries a structural
   `:status :error` plus the one-line `:error` string and a structured
   `:error-detail` (`Throwable->map`) so the model can branch on the
   exception class without parsing prose."
  [^Throwable t]
  (json-envelope {:status       :error
                  :error       (ex-message t)
                  :error-detail (jvm/throwable->map t)
                  :phase        "tool"}))

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

(defn- require-form
  "Build a Clojure `require` form string from :require and optional :alias.
   Returns nil when no :require is provided."
  [{:keys [require alias]}]
  (when (seq require)
    (if (seq alias)
      (format "(require '[%s :as %s])" require alias)
      (format "(require '[%s])" require))))

(defn- require-reload-form
  "Build a `:reload` require form for the auto-require retry path. The
   `:reload` flag forces Clojure to re-read the namespace source from
   the classpath instead of trusting AOT-compiled classes, which is the
   standard workaround for the classloader/AOT-staleness failure where
   `clojure/add-lib` mutates the classpath but the first `require` of a
   freshly-added lib with AOT classes fails (verify-round-1: nippy
   `CompilerException` despite the jar + source being present). Returns
   nil when no :require is provided."
  [{:keys [require alias]}]
  (when (seq require)
    (if (seq alias)
      (format "(require '[%s :as %s] :reload)" require alias)
      (format "(require '[%s] :reload)" require))))

(defn- namespace-require-error?
  "Heuristic: does `eval-result`'s `:error` look like a namespace / classloader
   resolution failure (the class of error where a `:reload` retry is the
   right move)? Matches CompilerException, namespace-not-found, and
   ClassNotFoundException signatures without parsing the structured
   `:error-detail` so the stub tests' plain-string `:error` still drive the
   retry path."
  [eval-result]
  (some? (when-let [err (:error eval-result)]
           (let [s (str err)]
             (or (re-find #"(?i)CompilerException" s)
                 (re-find #"(?i)namespace .* not found" s)
                 (re-find #"(?i)ClassNotFoundException" s)
                 (re-find #"(?i)FileNotFoundException" s)
                 (re-find #"(?i)not found" s))))))

(defn- eval-require-in-runtime
  "If a require form was requested, evaluate it in the runtime's default
   namespace and return the eval result. Otherwise return nil."
  [runtime require-form]
  (when require-form
    (proto/-eval runtime require-form {})))

(defn- add-lib-result-with-require
  "Merge the dependency-loading result with an optional auto-require result.
   The eval result is attached under :required and :required-error so the
   model can see whether the namespace was usable after loading.

   `:loaded?` is true ONLY when a require form was actually requested AND
   that require evaluated without error — never true merely because
   `add-libs` ran with no `:require` (audit 2026-07 rec #2: the old
   `:loaded? (nil? (:error eval-result))` was true whenever no require was
   passed, so the model could mistake \"add-libs ran, nothing required\"
   for \"the lib is usable\" and call e.g. `clerk/serve!` -> ClassNotFound).
   `:coord` echoes the resolved coordinate map so a version retry is
   auditable.

   When `:require-retried?` is true, the auto-require failed the first
   attempt and was retried with `:reload` to force a source recompile
   past classloader/AOT staleness (verify-round-1 fix). `:required` is
   the form that actually ran last (the `:reload` form on retry), and
   `:required-error` is the LAST attempt's error. On a retry that STILL
   fails, a `:hint` string is attached so the model does not spiral into
   jar-entry enumeration — it tells the model the jars are present but
   the namespace is unresolvable and to retry via `clojure/eval` with
   `:reload` or try a sub-namespace."
  [added-result require-form eval-result coords
   {:keys [retried? reload-form reload-result] :as _retry}]
  (let [final-res   (if retried? reload-result eval-result)
        still-fail? (and final-res (some? (:error final-res)))]
    (cond-> (assoc added-result :coord coords)
      require-form
      (assoc :required        require-form
             :required-error  (:error final-res)
             :loaded?         (boolean (and require-form
                                            (nil? (:error final-res)))))
      retried?
      (assoc :require-retried?   true
             :required-reload    reload-form)
      (and retried? still-fail?)
      (assoc :hint
             (str "The dependency jars were added to the classpath but the "
                  "namespace could not be required even after a :reload retry "
                  "(likely classloader/AOT staleness, not a missing dependency). "
                  "Do NOT enumerate jar entries. Options: (1) retry the require "
                  "with :reload via clojure/eval, (2) require a sub-namespace, "
                  "or (3) summarize the partial result.")))))

(deftype EvalTool [runtime config]
  tool/Tool
  (-name [_] "clojure/eval")
  (-description [_]
    "Evaluate Clojure code in a persistent in-process runtime namespace and return the result. The `code` string may contain multiple top-level forms; `def`s and `require`s persist across calls so you can build up state incrementally while prototyping. Returns JSON with `ns`, `forms` (count evaluated), `value` (pr-str of the last form), `values` (pr-str of EVERY form in evaluation order — use this when a multi-form showcase produces several results you need, e.g. def data -> show! -> port), `output` (captured stdout), `status` (`:ok`, `:truncated` when stdout was clipped, `:timeout`, or `:error`), `truncated?` (bool), `reader-eval-disabled?` (always true — reader-eval `#=` is OFF, so `#=` will NOT execute at read time), `error` (one-line exception or null), and `error-detail` (structured `{class message cause data trace}` on failure, null on success). Optional `ns` selects the target namespace. Optional `max-output-bytes` (int) raises the captured-stdout cap for THIS call — pass it when a render (e.g. a Clerk `show!` trace) exceeds the default 64KB cap so the output is not clipped mid-value. Optional `eval-timeout-ms` (int) widens the per-call timeout for a long-running render. Evaluation is time-limited; runaway loops are cancelled — on timeout `status` is `:timeout` and `error-detail` carries the timeout exception, so retry with a smaller computation rather than an identical call. Reader-eval (`#=`) is disabled; reader conditionals are allowed.")
  (-input-schema [_] schemas/EvalInput)
  (-output-schema [_] schemas/OutputString)
  (-invoke [_ args _ctx]
    (try
      (if-not (enabled? config)
        (disabled-envelope "clojure/eval")
        (let [{:keys [code repaired? method]} (paren-repair/repair-code (:code args))
              opts (cond-> {}
                     (:ns args) (assoc :ns (:ns args))
                     (:max-output-bytes args) (assoc :max-output-bytes (:max-output-bytes args))
                     (:eval-timeout-ms args) (assoc :eval-timeout-ms (:eval-timeout-ms args)))
              result (proto/-eval runtime code opts)]
          (json-envelope
           (cond-> result
             repaired? (assoc :paren-repaired? true
                              :paren-repair-method (name method))))))
      (catch Throwable t
        (error-envelope t)))))

(deftype AddLibTool [runtime config]
  tool/Tool
  (-name [_] "clojure/add-lib")
  (-description [_]
    "Load any Maven or Git dependency into the running JVM at runtime, without restarting, using Clojure 1.12's runtime dependency loading. This is the explicit tool for adding libraries that are NOT on the default classpath — for example, ring/ring-jetty-adapter to start a web server, org.clojure/data.json for JSON handling, metosin/reitit for routing, etc.\n\nProvide `lib` (e.g. \"org.clojure/data.json\") with an optional `version` (defaults to the latest RELEASE), or pass `coords`: an EDN map string of lib -> coordinate map for advanced/git coordinates. After the dependency loads, optionally pass `require` (namespace string, e.g. \"ring.adapter.jetty\") and `alias` (e.g. \"jetty\") to automatically require the namespace in the persistent runtime namespace so it is immediately usable. Returns JSON with `added` (libs loaded), `coord` (the resolved coordinate map that was actually used, for audit/version retries), `status` (`:ok`/`:error`), `required` (the require form that ran, if any), `loaded?` (true ONLY when a `:require` was requested AND that require succeeded — absent when no `:require` was passed, so do NOT assume the lib is usable without requiring it), `required-error` (if the require failed), `require-retried?` (true when the first require failed with a namespace/classloader error and was retried with `:reload` to force a source recompile past AOT staleness), `hint` (a recovery hint present when the `:reload` retry ALSO failed - do not enumerate jar entries; retry the require with `:reload` via clojure/eval or try a sub-namespace), `error` (one-line, null on success), and `error-detail` (structured `{class message cause data trace}` on failure). Always pass `:require` for the namespace you intend to call next, then check `loaded?` before using it. Requires the agent to run under the Clojure CLI; will fail with `:status :error` (not a crash) under the uberjar or native-image.")
  (-input-schema [_] schemas/AddLibInput)
  (-output-schema [_] schemas/OutputString)
  (-invoke [_ args _ctx]
    (try
      (cond
        (not (enabled? config))         (disabled-envelope "clojure/add-lib")
        (not (network-allowed? config)) (network-disabled-envelope)
                :else
        (let [coords        (parse-coords args)
              added-result  (proto/-add-libs runtime coords {})
              req-form      (require-form args)
              eval-result   (eval-require-in-runtime runtime req-form)
              ;; verify-round-1 fix: when the first auto-require fails with a
              ;; namespace/classloader resolution error, retry once with
              ;; :reload to force a source recompile past AOT staleness (the
              ;; nippy CompilerException where the jar+source are present but
              ;; the namespace is unresolvable). Only retry when add-libs
              ;; itself succeeded (status :ok) so a resolution failure is not
              ;; masked by a spurious reload attempt.
              retry?        (and req-form
                                 (nil? (:error added-result))
                                 (namespace-require-error? eval-result))
              reload-form   (when retry? (require-reload-form args))
              reload-result (when retry? (eval-require-in-runtime runtime reload-form))
              retry-info    (when retry?
                              {:retried?      true
                               :reload-form   reload-form
                               :reload-result reload-result})]
          (json-envelope (add-lib-result-with-require
                          added-result req-form eval-result coords
                          retry-info))))
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
