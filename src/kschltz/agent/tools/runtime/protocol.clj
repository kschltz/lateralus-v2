(ns kschltz.agent.tools.runtime.protocol
  "ClojureRuntime protocol for the lateralus runtime-eval tool suite.

   The runtime tools (`clojure/eval`, `clojure/add-lib`,
   `clojure/loaded-libs`) let the agent prototype by writing Clojure
   code and actually running it in-process, plus pull in new Maven
   dependencies at runtime (Clojure 1.12 `clojure.repl.deps/add-libs`).

   Every capability that crosses a process boundary is isolated behind
   this protocol so it can be stubbed in tests and Malli-instrumented at
   the implementation layer, matching the project rule that all
   external/network dependencies are protocol-bound and schema-checked.

   The methods divide into three risk tiers:

     - `-eval`         — runs arbitrary Clojure in a persistent runtime
                         namespace. Local (no network) but full code
                         execution; the implementation captures stdout,
                         the return value, and any thrown exception.
     - `-add-libs`     — NETWORK. Resolves and loads Maven/Git coordinates
                         at runtime so freshly required namespaces become
                         available without a JVM restart.
     - `-loaded-libs`  — Local. Lists the libs currently on the runtime
                         classpath.
     - `-capabilities` — Declarative map; MUST NOT raise. The tool layer
                         and any CLI summary branch on it without
                         try/catch.

   The first three methods return a plain data map (never a JSON string);
   the tool deftypes in `kschltz.agent.tools.runtime.tools` serialize the
   envelope    the model sees.")

(defprotocol ClojureRuntime
  "Pluggable backend for the `clojure/*` runtime-eval tool operations."
  (-eval [rt code opts]
    "Evaluate the Clojure source string `code` in a persistent runtime
     namespace. `opts` may carry `:ns` (target namespace name string)
     and per-call overrides for `:eval-timeout-ms` / `:max-output-bytes`.
     Returns a map
     `{:ns s :forms n :value (s|nil) :values [s] :output s
       :status :ok|:error|:timeout|:truncated :error (s|nil)
       :error-detail (m|nil)}` where `:value` is the `pr-str` of the
     last form's value (nil on error), `:values` is the `pr-str` of every
     form's value in order, `:output` is captured stdout, `:status` is a
     structural keyword the model can branch on, `:error` is a one-line
     formatted description of any thrown exception or a timeout, and
     `:error-detail` is the structured `Throwable->map` on failure.
     Never raises for ordinary evaluation failures — the failure is
     reported in `:error`/`:error-detail` with `:status :error`/`:timeout`.")
  (-add-libs [rt coords opts]
    "NETWORK. Add runtime dependencies. `coords` is a map of lib symbol
     -> coordinate map, e.g. `{org.clojure/data.json {:mvn/version \"2.5.0\"}}`.
     Resolves the coordinates (downloading from Maven/Git as needed) and
     makes them available on the live classpath. Returns
     `{:added [s] :status :ok|:error :error (s|nil) :error-detail (m|nil)}`
     listing the libs that were loaded. Network/resolution failures are
     reported in `:error`/`:error-detail` with `:status :error` rather
     than raised.")
  (-loaded-libs [rt]
    "Return a sorted vector of the lib names (strings) currently loaded
     in the runtime. Local — no network.")
  (-capabilities [rt]
    "Return a map `{:eval? bool :add-libs? bool :network? bool}`.
     MUST NOT raise."))

;; ---------------------------------------------------------------------------
;; Function schemas for the protocol surface.
;;
;; These declare the boundary contract so the JVM implementation can
;; Malli-instrument its delegate functions (input + output) per the
;; project rule for external/network dependencies. They are registered
;; here so both the protocol and its implementations share one source of
;; truth for the data shapes.
;; ---------------------------------------------------------------------------

(def Coords
  "Coordinate map handed to `-add-libs`: lib symbol -> coordinate map."
  [:map-of :symbol [:map-of :keyword :any]])

(def EvalResult
  "Return shape of `-eval`.

   `:status` is a structural keyword the model can branch on without
   string-matching `:error`: `:ok` (eval succeeded, output not clipped),
   `:truncated` (eval succeeded but captured stdout was clipped by the
   `:max-output-bytes` cap), `:timeout` (eval exceeded the per-call
   timeout), or `:error` (a form threw). `:value` is the `pr-str` of the
   LAST form's value (nil on error); `:values` is the `pr-str` of EVERY
   form's value in evaluation order (empty on error, partial on a
   mid-sequence throw). `:error` stays a one-line human string for
   back-compat; `:error-detail` carries the structuredThrowable->map
   `{:class :message :cause :data :trace}` so the model can branch on
   exception class without parsing prose. `:truncated?` mirrors the
   `:status :truncated` case as an explicit boolean. `:reader-eval-disabled?`
   is a constant `true` signal that the reader ran with `*read-eval*` false
   (reader-eval `#=` is off), so the model knows `#=` will not execute
   at read time (audit 2026-07 rec #8)."
  [:map
   [:ns :string]
   [:forms :int]
   [:value [:maybe :string]]
   [:values [:vector [:maybe :string]]]
   [:output :string]
   [:status [:enum :ok :error :timeout :truncated]]
   [:truncated? {:optional true} :boolean]
   [:reader-eval-disabled? {:optional true} :boolean]
   [:error [:maybe :string]]
   [:error-detail {:optional true} [:maybe :map]]])

(def AddLibsResult
  "Return shape of `-add-libs`. `:status` is `:ok` or `:error` so the
   model can branch without string-matching `:error`. `:error-detail`
   carries the structured `Throwable->map` on failure."
  [:map
   [:added [:vector :string]]
   [:status [:enum :ok :error]]
   [:error [:maybe :string]]
   [:error-detail {:optional true} [:maybe :map]]])

(defn capabilities?
  "Return true if `x` satisfies `ClojureRuntime`."
  [x]
  (satisfies? ClojureRuntime x))
