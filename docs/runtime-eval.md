# Runtime-eval tool suite

The runtime-eval tools let a lateralus agent **prototype in Clojure by
actually running code**: write a form, evaluate it in-process, inspect the
value/stdout/exception, and — when a library is missing — pull it in at
runtime without restarting the JVM. This is the Clojure analogue of a
scratch REPL baked directly into the agent loop.

| Tool | Purpose |
|------|---------|
| `clojure/eval` | Evaluate Clojure source in a persistent runtime namespace. |
| `clojure/add-lib` | Load a Maven/Git dependency onto the live classpath (Clojure 1.12 `add-libs`). |
| `clojure/loaded-libs` | List the libs currently loaded in the JVM. |

All three are isolated behind the `ClojureRuntime` protocol
(`kschltz.agent.tools.runtime.protocol`) so the network boundary can be
stubbed in tests and Malli-instrumented at the implementation layer,
matching the project rule that every external/network dependency is
protocol-bound and schema-checked.

## Integrant wiring

```clojure
:lateralus/runtime-tools {:enabled? true :network? true}
:lateralus/tool-registry [#ig/ref :lateralus/file-tools
                          #ig/ref :lateralus/self-awareness-tools
                          #ig/ref :lateralus/clojure-tools
                          #ig/ref :lateralus/runtime-tools
                          #ig/ref :lateralus/web-tools]
```

`:lateralus/runtime-tools` accepts a `RuntimeConfig`
(`kschltz.agent.tools.runtime.schemas/RuntimeConfig`):

| Key | Default | Meaning |
|-----|---------|---------|
| `:eval-ns` | `"lateralus.repl"` | Persistent namespace `clojure/eval` evaluates in. |
| `:eval-timeout-ms` | `30000` | Hard cap per `clojure/eval` call; runaway loops are cancelled. |
| `:max-output-bytes` | `65536` | Cap on captured stdout returned to the model. |
| `:enabled?` | `true` | Master switch; when `false`, every tool returns a `disabled` envelope. |
| `:network?` | `true` | When `false`, `clojure/add-lib` refuses to resolve deps; `clojure/eval` is unaffected. |
| `:runtime` | — | Inject a pre-built `ClojureRuntime` (test seam). |

It is wired into the JVM runtime config (`resources/lateralus/config.edn`)
and the in-memory `system/default-config`. The native-image config
(`resources/lateralus/native.edn`) enables `clojure/eval` with
`:network? false` so `clojure/add-lib` is blocked (`add-libs` needs the
Clojure CLI basis, which native-image does not ship).

## clojure/eval

Input: `{:code "..." :ns "optional.ns" :max-output-bytes int? :eval-timeout-ms int?}`. The `code` string may contain
multiple top-level forms. The runtime keeps a **persistent** namespace
(default `lateralus.repl`) with `clojure.core` referred, so `def`s and
`require`s from one call are visible to the next — you build state up
incrementally. Optional `max-output-bytes` / `eval-timeout-ms` are
positive ints that raise the captured-stdout cap / per-call timeout for
THIS call only (e.g. a Clerk `show!` render trace that exceeds the
default 64KB cap); when omitted the runtime config defaults apply.

Output (JSON):

```json
{"ns": "lateralus.repl", "forms": 2, "value": "42",
 "values": ["42"], "output": "", "status": "ok",
 "truncated?": false, "error": null, "error-detail": null}
```

- `value` is the `pr-str` of the **last** form's value (`null` on error).
- `values` is the `pr-str` of **every** form's value in evaluation order
  (partial on a mid-sequence throw) — use it when a multi-form showcase
  produces several results you need (def data -> show! -> port).
- `output` is captured stdout (truncated at `:max-output-bytes`).
- `status` is a structural keyword the model can branch on without parsing
  `error` prose: `ok` (clean, stdout not clipped), `truncated` (clean but
  stdout was clipped), `timeout`, or `error` (a form threw).
- `truncated?` mirrors the `truncated` status as an explicit boolean.
- `reader-eval-disabled?` is always `true` — the reader runs with
  `*read-eval*` false, so reader-eval `#=` will NOT execute at read time
  (reader conditionals are still allowed).
- `error` is a one-line formatted exception/timeout description, or `null`.
- `error-detail` is the structured `{class, message, cause, data, trace}`
  on failure, or `null` on success.

Evaluation runs on a future bounded by `:eval-timeout-ms`; on timeout the
future is cancelled and `error` reports the timeout. The reader runs with
`*read-eval*` bound `false` so `#=` cannot execute code at read time;
evaluation happens explicitly.

## clojure/add-lib

Input (one of):

- `{:lib "org.clojure/data.json" :version "2.5.0"}` — Maven coordinate
  (version defaults to the latest `RELEASE` when omitted).
- `{:coords "{org.clojure/data.json {:mvn/version \"2.5.0\"}}"}` — an EDN
  string of a full coordinate map, for git coordinates or multiple libs.

Output (JSON): `{"added": ["org.clojure/data.json"], "coord": {"org.clojure/data.json": {"mvn/version": "2.5.0"}}, "status": "ok", "error": null}`.

When `:require`/`:alias` are supplied, the envelope also carries
`required` (the require form), `loaded?` (true **only** when a `:require`
was requested AND that require succeeded — absent otherwise, so do not
assume the lib is usable without requiring it), and `required-error`.
`coord` echoes the resolved coordinate map for audit/version retries.
`status` is `ok` or `error`; on `error`, `error` is one-line and
`error-detail` is the structured `{class, message, cause, data, trace}`.

Under the hood this delegates to Clojure 1.12's
`clojure.repl.deps/add-libs`. The runtime owns a single
`DynamicClassLoader` shared by `clojure/eval` and `clojure/add-lib`, and
binds `clojure.core/*repl*` true around the call, so a freshly added
dependency is immediately `require`-able from the next `clojure/eval`:

```text
clojure/add-lib  {:lib "org.clojure/data.json" :version "2.5.0"}
clojure/eval     (require '[clojure.data.json :as json]) (json/write-str {:a 1})
=> "{\"a\":1}"
```

**Classloader refresh (verify-round-3).** After `add-libs` mutates the
shared `DynamicClassLoader` (adding the freshly resolved jars to its URL
list), the runtime wraps that mutated loader in a FRESH
`DynamicClassLoader` and installs it before the auto-require runs. This is
the fix for the AOT-class resolution failure observed when adding a lib
whose transitives ship AOT-compiled classes (e.g. `com.taoensso/nippy`,
pulled via `io.github.nextjournal/clerk`, whose `taoensso.nippy.impl`
references `taoensso.encore`): a `require` of the new lib against the
SAME mutated loader failed with a `CompilerException` even though the jar
+ source were present, and the round-2 `:reload` retry reproduced the
same failure (the root cause is class RESOLUTION, not source staleness).
Requiring against a fresh wrapper loader resolves it, so `loaded?` is
true after a successful add-lib + require. The `:reload` retry path stays
as a fallback but is no longer the primary fix.

Resolution requires the agent to run under the Clojure CLI (a
`clojure.basis` must be present). Network/resolution failures are reported
in `error` rather than raised.

## clojure/loaded-libs

No arguments. Returns `{"libs": ["clojure.string", ...]}` — handy for
checking whether an added dependency is available before requiring it.

## Safety

`clojure/eval` runs **arbitrary Clojure in-process** with the agent's full
permissions. Operators who want an air-gapped or read-only agent should
set `:enabled? false` (disables all three tools) or `:network? false`
(keeps eval, blocks runtime dependency loading). The eval timeout and
output cap bound runaway loops and output floods, but they do **not**
sandbox file/network/JVM access — treat this tool as you would a REPL.
