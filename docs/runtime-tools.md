# Runtime tool factory

Workbench can **define a real `Tool` mid-session**, call it on the next
model turn, and later **explicitly promote** that spec into reusable
source (a Tool namespace plus an optional interceptor plugin).

This is not `clojure_eval` pretending to be a tool. `tool_define` compiles
a protocol `Tool`, merges it into `:agent/tool-registry`, and patches
in-flight `:llm/request :tools` the same way MCP upserts do.

## Why this exists

`clojure_eval` / `clojure_add_lib` (Clojure 1.12 `add-libs`) are a scratch
REPL. They do not register a portable function name the model can call, and
an uberjar workbench cannot persist eval'd vars into `src/`.

The factory is the missing bridge:

1. **Define** — compile a persistable spec into a `Tool`.
2. **Test** — `tool_test` invokes the tool through the current guarded
   registry with real arguments and requires an exact expected string.
3. **Promote** — write on-disk Tool + plugin source. Promotion is explicit
   and requires a passing test of the current spec fingerprint.

## Tools

| Tool | Role |
|------|------|
| `tool_define` | Propose `:register-runtime-tool`. Compile + overlay happen in apply. |
| `tool_test` | Invoke with real arguments; record exact-output evidence for the current spec. |
| `tool_list_runtime` | Read-only inventory of ephemeral + promoted overlay names. |
| `tool_forget` | Drop a runtime tool from the session overlay. |
| `tool_promote` | Write files + catalog. `target=workspace` (default) or `project`. |

`tool_define` input:

```edn
{:name "add_two"
 :description "Add two integers"
 :input-schema "[:map [:a :int] [:b :int]]"
 :invoke "(fn [args _ctx] (str (+ (:a args) (:b args))))"
 ;; optional
 :libs "{org.clojure/data.json {:mvn/version \"2.5.0\"}}"
 :require "clojure.data.json"
 :alias "json"
 :interceptor-slot :observe
 :interceptor-enter "(fn [ctx] ctx)"}
```

`:libs` is EDN for `clojure.repl.deps/add-libs` (same network boundary as
`clojure_add_lib`, behind `ClojureRuntime`). Reader-eval is off.

## Promotion targets

| `target` | Where | Survives |
|----------|--------|----------|
| `workspace` (default) | `.lateralus/promoted/<name>/` + `catalog.edn` | Writable workspace (host or mounted Docker volume). `load-file`s immediately. |
| `project` | `src/kschltz/agent/tools/promoted/` + matching test | Source tree + `reload_runtime` / restart. |

`:as-plugin true` also writes an interceptor plugin that seeds the Tool
and, when present, the custom enter/leave/error fns.

Defining a tool never writes files. Redefinition invalidates prior test
evidence. Promotion is always explicit and refuses an untested current spec.

## Integrant

```clojure
:lateralus/factory-session {:workspace-root "." :dynamic {:enabled? true}}
:lateralus/factory-tools   {:session #ig/ref :lateralus/factory-session}
:lateralus/factory-plugin  {:session #ig/ref :lateralus/factory-session}
```

Wire `factory-tools` into `:lateralus/tool-registry`, pass
`:factory-session` to `:lateralus/tools-plugin`, and include
`:lateralus/factory-plugin` in `:lateralus/plugins`.

Lock with `:dynamic {:enabled? false}`.

## Workflow tools

`:lateralus/workflow-tools` is an in-process artifact engine (not a
step list). Actions declare `:needs` / `:produces`; `workflow_run`
computes a ready set and executes that set as one wave, repeating
until nothing is ready. Cycles and missing inputs are structured
`:blocked` results — the engine does not invent an order.

| Tool | Role |
|------|------|
| `workflow_register_action` | Upsert `{name, needs, produces, run}` |
| `workflow_seed` | Merge artifacts into the store |
| `workflow_run` | Schedule + execute; return waves / store / errors |
| `workflow_status` | Registered actions + current store |
| `workflow_clear` | Reset actions, store, or both |

`run` is `{:op :eval :code "(fn [store] …)"}`, `{:op :tool :name …}`,
or `{:op :literal :values {…}}`. No network I/O except through a
`:tool` that is itself protocol-bound.

## Protocols

- `ToolCompiler` — compile spec → Tool (+ optional interceptor). JVM impl
  uses in-process eval and `ClojureRuntime` for add-libs.
- `RuntimeToolStore` — define / forget / promote / rehydrate. Tools only
  emit transitions; apply reconciles the store (same pattern as MCP).

Specs persist on `:agent/runtime-tools` in `:agent/state-delta` so the
next exchange can rehydrate without files. The outer runtime replaces this
map wholesale when touched, so forget/promote removals cannot be resurrected
by deep merge. Rehydrate synchronizes the process-global factory overlay to
the active Workbench session: absent and changed ephemeral entries are
removed before missing/current specs are compiled.

Promoted catalog entries retain both generated source metadata and the
validated ToolSpec. Generated source remains primary; the spec is a recovery
recipe when a workspace path moves or disappears.

When the secrets plugin is active, it transforms the complete effective
registry and publishes that transform for same-exchange live-tool refreshes.
Runtime-created tools therefore receive `{{secret:label}}` substitution and
result redaction under the same use-without-seeing boundary as static tools.
