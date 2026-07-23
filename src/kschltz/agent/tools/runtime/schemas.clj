(ns kschltz.agent.tools.runtime.schemas
  "Malli schemas for the lateralus runtime-eval tool suite.

   Three families, mirroring `kschltz.agent.tools.web.schemas`:

   1. **Op input/output** — one pair per tool op (`clojure/eval`,
      `clojure/add-lib`, `clojure/loaded-libs`). Inputs are Malli maps;
      outputs are `:string` because `kschltz.agent.tool/invoke-tool`
      validates every tool result with `string?` before handing it to
      the model. The data envelope is JSON-encoded inside the tool
      deftype, not here.

   2. **Runtime data shapes** — `EvalResult`, `AddLibsResult`, `Coords`.
      Re-exported from `kschltz.agent.tools.runtime.protocol` so the JVM
      implementation can Malli-instrument the protocol delegate functions
      (input + output) against one source of truth.

   3. **Config** — `RuntimeConfig` encodes the per-call and per-registry
      knobs (target namespace, eval timeout, output cap, and the
      `:enabled?` / `:network?` safety toggles)."
  (:require [kschltz.agent.tools.runtime.protocol :as proto]))

;; ---------------------------------------------------------------------------
;; Op input/output schemas
;; ---------------------------------------------------------------------------

(def EvalInput
  "Input schema for `clojure/eval`. `code` is a required, non-empty
   Clojure source string that may contain multiple top-level forms.
   `ns` optionally names the persistent runtime namespace to evaluate
   in (defs persist there across calls).

   Optional per-call overrides (audit 2026-07 rec #8): `max-output-bytes`
   raises the captured-stdout cap for THIS call (e.g. a Clerk `show!`
   render trace that exceeds the default 64KB), and `eval-timeout-ms`
   widens the per-call timeout (e.g. a long render). When omitted the
   runtime config defaults apply. Both are positive ints."
  [:map
   [:code [:string {:min 1}]]
   [:ns {:optional true} :string]
   [:max-output-bytes {:optional true} [:int {:min 1}]]
   [:eval-timeout-ms  {:optional true} [:int {:min 1}]]])

(def AddLibInput
  "Input schema for `clojure/add-lib`. Either supply `lib` (a Maven
   coordinate string like \"org.clojure/data.json\") with an optional
   `version`, or supply `coords`: an EDN string of a full coordinate
   map (`{lib {:mvn/version \"...\"}}` or git coords) for advanced use.

   After the dependency is loaded, the tool can automatically `require`
   a namespace from it so the model can use it immediately: pass
   `:require` (namespace string) and optionally `:alias` (short alias).
   The require is evaluated in the persistent runtime namespace."
  [:map
   [:lib {:optional true} :string]
   [:version {:optional true} :string]
   [:coords {:optional true} :string]
   [:require {:description "Namespace to require after loading, e.g. \"ring.adapter.jetty\"" :optional true} :string]
   [:alias {:description "Alias for :require, e.g. \"jetty\" to produce [ring.adapter.jetty :as jetty]" :optional true} :string]])

(def LoadedLibsInput
  "Input schema for `clojure/loaded-libs`. Takes no arguments."
  [:map {:closed true}])

(def OutputString
  "All runtime tools JSON-serialize their envelope and return a string."
  :string)

;; ---------------------------------------------------------------------------
;; Runtime data shapes (re-exported from the protocol ns)
;; ---------------------------------------------------------------------------

(def Coords proto/Coords)
(def EvalResult proto/EvalResult)
(def AddLibsResult proto/AddLibsResult)

;; ---------------------------------------------------------------------------
;; Config
;; ---------------------------------------------------------------------------

(def RuntimeConfig
  "Config for the runtime-eval registry and per-call overrides.

     :eval-ns          — name of the persistent runtime namespace
                         (default \"lateralus.repl\").
     :eval-timeout-ms  — hard cap on a single `clojure/eval` call; on
                         timeout the future is cancelled and an error is
                         reported (default 30000).
     :max-output-bytes — cap on captured stdout returned to the model
                         (default 65536).
     :enabled?         — master switch; when false every tool returns a
                         disabled envelope (default true).
     :network?         — when false `clojure/add-lib` refuses to resolve
                         dependencies (default true). `clojure/eval` is
                         unaffected.

   `:runtime` may also appear here to inject a pre-built
   `ClojureRuntime` (the test seam); it is `:any` so the schema does not
   need to know about deftypes."
  [:map
   [:eval-ns          {:optional true} :string]
   [:eval-timeout-ms  {:optional true} :int]
   [:max-output-bytes {:optional true} :int]
   [:enabled?         {:optional true} :boolean]
   [:network?         {:optional true} :boolean]
   [:runtime          {:optional true} :any]])
