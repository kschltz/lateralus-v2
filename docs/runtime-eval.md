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
and the in-memory `system/default-config`. It is **not** part of the
native-image config: GraalVM native-image cannot compile arbitrary forms
at runtime, and `add-libs` needs the Clojure CLI basis.

## clojure/eval

Input: `{:code "..." :ns "optional.ns"}`. The `code` string may contain
multiple top-level forms. The runtime keeps a **persistent** namespace
(default `lateralus.repl`) with `clojure.core` referred, so `def`s and
`require`s from one call are visible to the next — you build state up
incrementally.

Output (JSON):

```json
{"ns": "lateralus.repl", "forms": 2, "value": "42",
 "output": "", "error": null}
```

- `value` is the `pr-str` of the **last** form's value (`null` on error).
- `output` is captured stdout (truncated at `:max-output-bytes`).
- `error` is a formatted exception/timeout description, or `null`.

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

Output (JSON): `{"added": ["org.clojure/data.json"], "error": null}`.

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
