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
   the exception class, message, and any ex-data. Kept as the one-line
   `:error` value for back-compat; the structured form is `throwable->map`."
  [^Throwable t]
  (let [data (ex-data t)]
    (cond-> (str (.getName (class t)) ": " (ex-message t))
      (some? data) (str " " (pr-str data)))))

(defn throwable->map
  "Build a model-readable structured map from a Throwable for the
   `:error-detail` envelope field: `{:class :message :cause :data :trace}`.
   `:trace` holds the top 5 stack elements rendered as
   `class.method(file:line)` strings so the model can see where the
   failure originated without re-tokenizing a full stack. `:cause` is a
   one-level nested map for the chained cause (class + message only).
   Never raises — returns `{:class \"<nil>\" ...}` on a nil throwable."
  [^Throwable t]
  (if (nil? t)
    {:class "<nil>" :message nil :cause nil :data nil :trace []}
    (let [cause (ex-cause t)
          data  (ex-data t)
          st    (.getStackTrace t)]
      {:class   (.getName (class t))
       :message (ex-message t)
       :cause   (when cause
                  {:class   (.getName (class cause))
                   :message (ex-message cause)})
       :data    data
       :trace   (mapv (fn [^StackTraceElement e]
                        (str (.getClassName e) "."
                             (.getMethodName e) "("
                             (.getFileName e) ":"
                             (.getLineNumber e) ")"))
                      (take 5 st))})))

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

(defn refresh-classloader
  "Wrap a `DynamicClassLoader` (already mutated by
   `clojure.repl.deps/add-libs`, which added the freshly resolved jars to
   its URL list) in a FRESH `DynamicClassLoader` and return it.

   This is the verify-round-3 fix for the AOT-class resolution failure
   observed when adding a lib whose transitives ship AOT-compiled classes
   (e.g. `com.taoensso/nippy`, pulled via `io.github.nextjournal/clerk`,
   whose `taoensso.nippy.impl` references `taoensso.encore`): after
   `add-libs` mutates the shared classloader, a `require` of the new lib
   against the SAME mutated classloader fails with a `CompilerException`
   (`No such var: enc/latom`-style), even though the jar + source are now
   present. Re-reading source via `:reload` (the round-2 retry) reproduces
   the SAME failure because the root cause is class RESOLUTION against
   the just-mutated classloader's cached lookup state, not source
   staleness.

   Wrapping the mutated classloader in a brand-new `DynamicClassLoader`
   (whose parent is the mutated one, so the new jars stay visible)
   restores resolution — confirmed live: `require` of `taoensso.nippy`
   succeeds against the fresh wrapper while it fails against the mutated
   original. The `:reload` retry path in `tools.clj` stays as a fallback
   but is no longer the primary fix."
  ^DynamicClassLoader [^DynamicClassLoader cl]
  (DynamicClassLoader. cl))

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
   namespaces added via `clojure/add-lib` are visible (may be nil).

   The envelope carries a structural `:status` keyword
   (`:ok`/`:error`/`:timeout`/`:truncated`) so the model can branch
   without parsing `:error` prose: `:ok` when every form evaluated and
   stdout was not clipped, `:truncated` when evaluation succeeded but
   captured stdout exceeded `:max-output-bytes`, `:timeout` on a
   per-call timeout, `:error` when a form threw. `:values` holds the
   `pr-str` of EVERY form's value in evaluation order (partial on a
   mid-sequence throw) so a multi-form showcase (def data -> show! ->
   port) does not discard the earlier results. `:error-detail` carries
   the structured `throwable->map` on failure."
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
                         ;; Evaluate every top-level form in order,
                         ;; accumulating values so a multi-form showcase
                         ;; does not discard the earlier results. A
                         ;; mid-sequence throw returns the partial
                         ;; values alongside the throwable so `:values`
                         ;; stays useful even when a later form fails.
                         ;; The per-form try lives in a separate fn so
                         ;; the loop's recur does not cross a try.
                         (let [eval-form (fn [form]
                                            (try
                                              [:value (clojure.core/eval form)]
                                              (catch Throwable t [:throw t])))]
                           (loop [remaining forms
                                  acc      []]
                             (if (empty? remaining)
                               {:values acc}
                               (let [r (eval-form (first remaining))]
                                 (if (= (first r) :value)
                                   (recur (rest remaining)
                                          (conj acc (second r)))
                                   {:throwable (second r) :values acc}))))))))))
        raw-output (str sw)
        clipped?   (and (string? raw-output) (> (count raw-output) max-out))
        output     (if clipped? (truncate raw-output max-out) raw-output)
        timed-out? (= outcome ::timeout)
        threw?     (and (map? outcome) (:throwable outcome))
        values     (if (map? outcome) (:values outcome) [])
        last-val   (when (and (not timed-out?) (seq values))
                     (pr-str (peek values)))
        status     (cond
                     timed-out? :timeout
                     threw?     :error
                     clipped?   :truncated
                     :else      :ok)
        err-throw  (cond
                     timed-out? (TimeoutException.
                                 (format "Evaluation timed out after %d ms" timeout))
                     threw?     (:throwable outcome)
                     :else      nil)]
    {:ns           (str ns-sym)
     :forms        (count forms)
     :value        last-val
     :values       (mapv #(when (some? %) (pr-str %)) values)
     :output       output
     :status       status
     :truncated?   clipped?
     :reader-eval-disabled? true
     :error        (when err-throw (throwable->str err-throw))
     :error-detail (when err-throw (throwable->map err-throw))}))

(defn add-libs*
  "Resolve and load runtime dependencies `coords` (a lib-symbol ->
   coordinate-map map) via `clojure.repl.deps/add-libs`. NETWORK. Returns
   an `AddLibsResult`; resolution failures are reported in `:error` and
   `:error-detail` with `:status :error` rather than raised.

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
      {:added        (mapv str added)
       :status       :ok
       :error        nil
       :error-detail nil})
    (catch Throwable t
      {:added        []
       :status       :error
       :error        (throwable->str t)
       :error-detail (throwable->map t)})))

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

(deftype JvmRuntime [config ^:volatile-mutable classloader]
  proto/ClojureRuntime
  (-eval [_ code opts]
    (let [cfg    (merge config opts)
          ns-sym (symbol (or (:ns opts) (:eval-ns config) default-eval-ns))]
      (eval-code ns-sym code cfg classloader)))
  (-add-libs [_ coords opts]
    ;; `add-libs*` mutates `classloader` (the shared DynamicClassLoader,
    ;; installed as the context classloader) by adding the freshly
    ;; resolved jars to its URL list. On a SUCCESSFUL resolution we then
    ;; refresh the classloader: wrap the mutated loader in a fresh
    ;; `DynamicClassLoader` and install it as the runtime's classloader so
    ;; the NEXT `-eval` (the AddLibTool auto-require, or a follow-up
    ;; `clojure/eval`) resolves AOT-transitive classes through a clean
    ;; loader state. See `refresh-classloader` for the why. Refresh only
    ;; when the current loader is a `DynamicClassLoader` (it always is for
    ;; `jvm-runtime`, but a test seam could pass something else).
    (let [res (add-libs* coords (merge config opts) classloader)]
      (when (and (= :ok (:status res))
                 (instance? DynamicClassLoader classloader))
        (set! classloader (refresh-classloader classloader)))
      res))
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
