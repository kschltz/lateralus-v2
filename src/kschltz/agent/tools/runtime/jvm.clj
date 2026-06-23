(ns kschltz.agent.tools.runtime.jvm
  "JVM `ClojureRuntime` implementation for the runtime-eval tool suite.

   `eval-code` reads every top-level form from a source string and
   evaluates them in order inside a persistent, per-name runtime
   namespace, so a `def` in one call is visible to the next — exactly
   what an agent needs to prototype incrementally. stdout is captured,
   the last form's value is `pr-str`'d, and any thrown exception is
   formatted into `:error` rather than propagated. A timeout runs the
   evaluation on a future so a runaway loop cannot wedge the agent.

   `add-libs*` is the **network** boundary: it delegates to Clojure
   1.12's `clojure.repl.deps/add-libs`, which resolves and downloads
   Maven/Git coordinates and loads them onto the live classpath. This
   only works when the JVM was started by the Clojure CLI (a
   `clojure.basis` and a dynamic classloader must be present); otherwise
   a clear error is returned in `:error`.

   Per the project rule for external/network dependencies, both delegate
   functions are Malli-instrumented (input + output) via `m/=>` plus
   `malli.instrument/instrument!` scoped to this namespace, on top of the
   protocol that already isolates them."
  (:require [kschltz.agent.tools.runtime.protocol :as proto]
            [kschltz.agent.tools.runtime.schemas :as schemas]
            [malli.core :as m]
            [malli.instrument :as mi])
  (:import [clojure.lang DynamicClassLoader RT]
           [java.io PushbackReader StringReader StringWriter]
           [java.util.concurrent TimeoutException]))

(def default-eval-ns
  "Default persistent namespace used by `clojure/eval`."
  "lateralus.repl")

(def default-eval-timeout-ms
  "Default per-call evaluation timeout."
  30000)

(def default-max-output-bytes
  "Default cap on captured stdout returned to the model."
  (* 64 1024))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- truncate
  "Truncate `s` to at most `max-bytes` characters, appending a marker
   when content was dropped."
  [s max-bytes]
  (if (and (string? s) (> (count s) max-bytes))
    (str (subs s 0 max-bytes)
         (format "\n... [output truncated at %d chars]" max-bytes))
    s))

(defn- throwable->str
  "Format a Throwable into a compact, model-readable string including
   the exception class, message, and any ex-data."
  [^Throwable t]
  (let [data (ex-data t)]
    (cond-> (str (.getName (class t)) ": " (ex-message t))
      (some? data) (str " " (pr-str data)))))

(defn- read-forms
  "Read every top-level form from `code`. Reader-eval (`#=`) is disabled
   during read so the reader itself never executes code; evaluation
   happens explicitly in `eval-code`. Reader conditionals are allowed."
  [code]
  (let [eof (Object.)
        pbr (PushbackReader. (StringReader. code))]
    (binding [*read-eval* false]
      (loop [acc []]
        (let [form (read {:eof eof :read-cond :allow} pbr)]
          (if (identical? form eof)
            acc
            (recur (conj acc form))))))))

(defonce ^:private initialized-nses
  ;; Tracks runtime namespaces that have already had clojure.core
  ;; referred into them, so we only pay that cost once per ns name.
  (atom #{}))

(defn- ensure-ns!
  "Return the runtime namespace named `ns-sym`, creating it and referring
   clojure.core on first use so ordinary code resolves without the caller
   writing an `ns`/`require` preamble."
  [ns-sym]
  (let [the-ns (create-ns ns-sym)]
    (when-not (contains? @initialized-nses ns-sym)
      (binding [*ns* the-ns]
        (refer-clojure))
      (swap! initialized-nses conj ns-sym))
    the-ns))

(defn- run-with-timeout
  "Run thunk `f` on a future, returning its value or `::timeout` if it
   does not finish within `ms`. On timeout the future is cancelled so a
   runaway evaluation thread is interrupted."
  [ms f]
  (let [fut (future (f))
        v   (deref fut ms ::timeout)]
    (when (= v ::timeout)
      (future-cancel fut))
    v))

(defn new-classloader
  "Build a fresh `DynamicClassLoader` wrapping the current base loader.
   `clojure.repl.deps/add-libs` requires the thread context classloader
   to be a `DynamicClassLoader`, and Clojure's `require` resolves through
   the context classloader, so one shared instance per runtime lets a
   `clojure/add-lib` followed by a `clojure/eval` see the new namespaces."
  ^DynamicClassLoader []
  (DynamicClassLoader. (RT/baseLoader)))

(defn- with-classloader
  "Run thunk `f` with `cl` installed as the current thread's context
   classloader, restoring the previous loader afterward. When `cl` is nil
   (a test seam may omit it) `f` runs unchanged."
  [^ClassLoader cl f]
  (if cl
    (let [t    (Thread/currentThread)
          prev (.getContextClassLoader t)]
      (try
        (.setContextClassLoader t cl)
        (f)
        (finally
          (.setContextClassLoader t prev))))
    (f)))

;; ---------------------------------------------------------------------------
;; Protocol delegate functions (Malli-instrumented)
;; ---------------------------------------------------------------------------

(defn eval-code
  "Evaluate the Clojure source string `code` in the persistent namespace
   `ns-sym`. Returns an `EvalResult` map. `config` supplies
   `:eval-timeout-ms` and `:max-output-bytes` overrides. `cl` is the
   shared `DynamicClassLoader` installed as the context classloader so
   namespaces added via `clojure/add-lib` are visible (may be nil)."
  [ns-sym code config cl]
  (let [forms   (read-forms code)
        the-ns  (ensure-ns! ns-sym)
        sw      (StringWriter.)
        max-out (or (:max-output-bytes config) default-max-output-bytes)
        timeout (or (:eval-timeout-ms config) default-eval-timeout-ms)
        outcome (run-with-timeout
                 timeout
                 (fn []
                   (with-classloader cl
                     (fn []
                       (binding [*ns* the-ns
                                 *out* sw]
                         (try
                           {:value (reduce (fn [_ form] (clojure.core/eval form))
                                           nil forms)}
                           (catch Throwable t
                             {:throwable t})))))))]
    {:ns     (str ns-sym)
     :forms  (count forms)
     :value  (when (and (map? outcome) (not (:throwable outcome)))
               (pr-str (:value outcome)))
     :output (truncate (str sw) max-out)
     :error  (cond
               (= outcome ::timeout)
               (throwable->str (TimeoutException.
                                (format "Evaluation timed out after %d ms" timeout)))

               (:throwable outcome)
               (throwable->str (:throwable outcome))

               :else nil)}))

(defn add-libs*
  "Resolve and load runtime dependencies `coords` (a lib-symbol ->
   coordinate-map map) via `clojure.repl.deps/add-libs`. NETWORK. Returns
   an `AddLibsResult`; resolution failures are reported in `:error`.

   `clojure.repl.deps/add-libs` refuses to run unless `clojure.core/*repl*`
   is true and the thread context classloader is a `DynamicClassLoader`.
   This runtime is a controlled programmatic REPL, so `cl` (the shared
   `DynamicClassLoader`) is installed as the context classloader and the
   var is bound true (when it exists — it was introduced in Clojure 1.12)
   around the call."
  [coords _config cl]
  (try
    (let [add-libs (requiring-resolve 'clojure.repl.deps/add-libs)
          repl-var (resolve 'clojure.core/*repl*)
          added    (with-classloader cl
                     (fn []
                       (if repl-var
                         (with-bindings {repl-var true} (add-libs coords))
                         (add-libs coords))))]
      {:added (mapv str added) :error nil})
    (catch Throwable t
      {:added []
       :error (throwable->str t)})))

(defn loaded-libs*
  "Return a sorted vector of the currently loaded lib names."
  []
  (mapv str (sort (loaded-libs))))

;; ---- Malli function schemas + instrumentation ----------------------------

(m/=> eval-code   [:=> [:cat :symbol :string schemas/RuntimeConfig [:maybe :any]] schemas/EvalResult])
(m/=> add-libs*   [:=> [:cat schemas/Coords schemas/RuntimeConfig [:maybe :any]] schemas/AddLibsResult])
(m/=> loaded-libs* [:=> [:cat] [:vector :string]])

(defn instrument!
  "Instrument this namespace's protocol delegate functions so their
   inputs and outputs are Malli-validated at the boundary. Idempotent."
  []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.tools.runtime.jvm)]}))

(instrument!)

;; ---------------------------------------------------------------------------
;; Runtime
;; ---------------------------------------------------------------------------

(deftype JvmRuntime [config classloader]
  proto/ClojureRuntime
  (-eval [_ code opts]
    (let [cfg    (merge config opts)
          ns-sym (symbol (or (:ns opts) (:eval-ns config) default-eval-ns))]
      (eval-code ns-sym code cfg classloader)))
  (-add-libs [_ coords opts]
    (add-libs* coords (merge config opts) classloader))
  (-loaded-libs [_]
    (loaded-libs*))
  (-capabilities [_]
    {:eval?     true
     :add-libs? true
     :network?  (boolean (get config :network? true))}))

(defn jvm-runtime
  "Return a `JvmRuntime` configured with `config` (see
   `kschltz.agent.tools.runtime.schemas/RuntimeConfig`).

   The runtime owns a single `DynamicClassLoader` shared by `-eval` and
   `-add-libs` so a dependency loaded at runtime becomes visible to later
   evaluations in the same session."
  ([] (jvm-runtime {}))
  ([config] (->JvmRuntime (or config {}) (new-classloader))))
