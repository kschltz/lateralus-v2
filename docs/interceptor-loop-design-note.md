# Interceptor Architecture — Everything Is an Interceptor

> **Historical design note — superseded.** This document records the pre-v2 interceptor-chain thesis. The current architecture is documented in [`docs/architecture.md`](./architecture.md) and implemented in `src/kschltz/agent/chain.clj`, `src/kschltz/agent/plugin.clj`, `src/kschltz/agent/plugins/base.clj`, `src/kschltz/agent/plugins/memory.clj`, `src/kschltz/agent/interceptors.clj`, and `src/kschltz/agent/interceptors/schema.clj`. Do not use this file as a spec for new work.

Status: **superseded by the v2 implementation** (commits ed22b45+).
Scope: new `kschltz.agent.chain` (engine), new `kschltz.agent.interceptors.*`,
rewrite of `kschltz.agent.loop`, surface changes in `kschltz.agent.core`

**Drift note:** this plan was written before the v2 rewrite and references
some v1 stages that were deliberately dropped from the v2 implementation:
`api-error-retry`, `tool-error-retry`, `wrap-up`. These were tightly coupled
to v1's `loop/` namespace and not ported. In v2, retry/tool-error handling
lives in plugin slots (`:guard`, `:enrich`, etc.) rather than dedicated
chain stages, and the engine treats any unhandled error as a hard rethrow
(see `kschltz.agent.chain/execute`). Treat the rest of this doc as design
context, not a current implementation spec.

## Thesis

Pedestal's architecture — a context map flowing through a queue of
`{:enter :leave :error}` stages with dynamic queue manipulation — but **our
own engine, not the Pedestal dependency**. And taken to its conclusion:
every plugin and inner working of the agent is an interceptor.

There is exactly one extension mechanism. The agent core becomes:

```
engine (≈100 lines) + ctx schema + a default chain of interceptors
```

Everything currently hardwired into `loop.clj`/`core.clj` becomes a stage in
that chain:

| Today | Becomes |
|---|---|
| `context/compose-context` call inside `llm-call` | `compose-context` interceptor (enter) |
| memory recall / retrieval injection | `memory-recall` interceptor (enter) — the RAG slot |
| `llm/call` + retry/trim logic | `llm-call` interceptor delegating to `LlmClient` protocol |
| tool-call parse/validate/execute inner loop | `dispatch` + per-tool interceptors via re-enqueue |
| `add-repl-eval-tool!`, `add-web-search-tool!`, … (8 ad-hoc installers) | plugins = interceptor bundles (see below) |
| `store-exchange` persistence | `store-exchange` interceptor (leave) |
| history capping + agent-state delta | `update-history` interceptor (leave) |
| `:on-response`/`:on-error`/`:on-thought` callbacks | `notify` interceptor (leave + error) |
| `safety/` checks | `guardrail` interceptor(s) at named slots |
| scattered `try/catch` | `error-boundary` interceptor (`:error`) |

## 1. The engine — `kschltz.agent.chain`

Own implementation, Pedestal semantics. Target ≤ ~120 lines, zero new deps.

```clojure
(ns kschltz.agent.chain)

;; An interceptor is a plain map:
;;   {:name kw, :enter (fn [ctx] ctx'), :leave (fn [ctx] ctx'), :error (fn [ctx ex] ctx')}
;; All keys optional except :name.

(defn enqueue   [ctx interceptors] ...) ; append to ::queue
(defn terminate [ctx]              ...) ; drop ::queue, begin unwinding ::stack
(defn execute   [ctx interceptors] ...) ; run to completion, return final ctx
```

Semantics (identical to Pedestal, synchronous only):
- `:enter` in queue order; each executed interceptor is pushed onto `::stack`
- queue empty (or `terminate`) → `:leave` in reverse stack order
- exception in any stage → switch to error mode: walk the stack calling
  `:error` until one returns a ctx without `::error` (handled), then resume
  `:leave` from there
- interceptors may `enqueue` more work mid-flight — this is how the tool
  loop and retries are expressed

Engine state lives under namespaced keys (`::queue`, `::stack`, `::error`)
so it never collides with domain keys. Property: `execute` is a pure
function of `(ctx, interceptors)` given pure stages — trivially testable.

Why own it rather than depend on `io.pedestal/pedestal.interceptor`:
- the artifact drags in core.async and Pedestal's logging; we need none of it
- we want schema instrumentation *inside* the engine (below), which is
  awkward to bolt onto Pedestal
- the contract is small and frozen; the cost is ~120 lines + tests once

## 2. The ctx — one map, intentionally open

Created per exchange in the (thin) outer loop, threaded through everything:

```clojure
{:agent/ref          ag        ; clojure.core agent, for sends only
 :agent/state        state     ; immutable snapshot at exchange start
 :agent/state-delta  {}        ; staged mutations, merged back by outer loop
 :exchange/items     items     ; drained queue items {:text :promise :handler}
 :exchange/user-text "..."
 :exchange/response  nil
 :exchange/error     nil
 :turn/messages      []        ; accumulating OpenAI messages
 :turn/transcript    []
 :turn/depth         0
 :turn/retries       0
 :llm/request        nil       ; composed messages + params
 :llm/response       nil       ; raw provider response
 :llm/api-error      nil
 :tool/calls         nil
 :tool/results       nil
 :memory/recalled    nil       ; RAG: facts/messages injected this turn
 :memory/stored      nil}
```

`Ctx` Malli schema in `src/kschltz/agent/interceptors/schema.clj`. The
schema is intentionally open: it validates only a few instrumentation and
traceability keys, leaving domain keys free for plugins to extend without a
schema migration. The engine does not enforce a closed ctx shape after every
stage; Malli instrumentation is used for Integrant config validation and
protocol I/O boundaries, not for the context map itself.

Per project convention, anything that touches the network stays behind a
protocol with Malli-instrumented implementation fns:
- `LlmClient` (wraps today's `llm/call`)
- `WebSearch` (wraps `tools/web.clj` internals)
- `Embedder` (wraps the LangChain4j embedding calls used by recall)
The interceptors hold only protocol references taken from `:agent/state`,
so every stage is testable with in-memory fakes.

## 3. Plugins = interceptor bundles

The unification the previous draft missed. A plugin is data:

```clojure
{:plugin/name  :web-search
 :interceptors {:enrich   []                    ; e.g. RAG-style pre-LLM work
                :tools    [web-search-tool-ix]  ; tool interceptors (see §4)
                :persist  []                    ; post-response leave work
                :observe  [search-metrics-ix]}} ; tracing/metrics
```

Named slots in the default chain (in enter order):

```
:guard → :enrich → :compose → :llm → :dispatch/tools → :finalize
                                          (leave order)
:observe ← :notify ← :persist ← :history
```

`make-agent` takes `:plugins [...]`; chain assembly is a pure fold of
plugin maps into the default chain. The existing `add-*-tool!` fns become
one-line plugin constructors (kept as deprecated aliases for one release):

- `remember` plugin → tool interceptor + `:persist` hook (fact storage) +
  `:enrich` hook (retrieval injection pre-filter — today's `5497b47` logic)
- `repl` / `clj-edit` / `portal` / `web` plugins → tool interceptors only
- memory itself becomes the first-class `memory` plugin: `memory-recall`
  in `:enrich`, `store-exchange` in `:persist`
- `safety/` becomes a `guardrail` plugin in `:guard` (and optionally a
  post-LLM screen before `:dispatch`)

Consequence: `core.clj` no longer knows what a "tool" or "memory" is. It
knows how to assemble a chain and run it.

## 4. Tools as interceptors

A tool is an interceptor whose `:enter` runs when `dispatch` routes a
tool-call to it. Tool metadata (OpenAI function def, Malli arg schema)
lives on the interceptor map:

```clojure
{:name        :tool/web-search
 :tool/def    {...openai function def...}
 :tool/schema [:map [:query :string]]
 :enter       (fn [ctx] (run-search ctx))}   ; reads :tool/current-call, writes :tool/results
```

`dispatch` is the loop brain, replacing `loop/recur` with re-enqueueing:

```clojure
{:name :dispatch
 :enter (fn [ctx]
          (cond
            ;; tool calls + depth budget → enqueue the matching tool
            ;; interceptors, then loop back through llm-call + dispatch
            (pending-tool-calls? ctx)
            (-> ctx (update :turn/depth inc)
                    (chain/enqueue (concat (route-tools ctx)
                                           [tool-error-retry llm-call
                                            parse-response dispatch])))
            (depth-exhausted? ctx) (chain/enqueue ctx [wrap-up])
            (api-error? ctx)       (retry-or-fail ctx)
            (blank? ctx)           (retry-or-finish ctx)
            :else                  (finalize-response ctx)))}
```

Validation/coercion (today's `tools/coerce-args`, `validate-args`) becomes a
generic `tool-validate` interceptor enqueued before each tool interceptor —
tools never see invalid args.

What this buys over the current code:
- `:leave` stages run exactly once no matter how many tool iterations
  happened — the stack unwind replaces manual transcript plumbing
- one `:error` chain replaces three scattered `try/catch` blocks
- a tracing plugin sees *every* stage uniformly, including each tool call
- adding a capability never means editing `loop.clj` again

## Current chain at a glance

The assembled default chain today (from `src/kschltz/agent/plugin.clj` and `src/kschltz/agent/plugins/base.clj`) is:

| Order | Slot | Interceptor | Source |
|-------|------|-------------|--------|
| enter | `:guard` | `error-boundary` | `src/kschltz/agent/interceptors.clj` |
| enter | `:guard` | `bind-llm-client` | `src/kschltz/agent/interceptors.clj` |
| enter | `:enrich` | `memory-recall` | `src/kschltz/agent/plugins/memory.clj` |
| enter | `:compose` | `compose-context` | `src/kschltz/agent/interceptors.clj` |
| enter | `:llm` | `llm-call` | `src/kschltz/agent/interceptors.clj` |
| enter | `:llm` | `parse-response` | `src/kschltz/agent/interceptors.clj` |
| enter | `:dispatch` | `dispatch` | `src/kschltz/agent/interceptors.clj` |
| leave | `:history` | `store-exchange` | `src/kschltz/agent/interceptors.clj` |
| leave | `:persist` | `memory-persist` | `src/kschltz/agent/plugins/memory.clj` |
| leave | `:observe` | `deliver-responses` | `src/kschltz/agent/interceptors.clj` |
| leave | `:notify` | `notify` | `src/kschltz/agent/interceptors.clj` |

The source of truth is `src/kschltz/agent/interceptors.clj` for base interceptors and `src/kschltz/agent/plugins/base.clj` / `src/kschltz/agent/plugins/memory.clj` for plugin contributions.

## Relationship to in-progress cards

Three active kanban cards touch this chain directly:

- **[006] Replace shallow state merge with deep or explicit state update** — will change how `:agent/state-delta` is merged by the runtime after the chain unwinds; the interceptor contract (`:agent/state-delta` staging) is unchanged.
- **[007] Pre-wire dependencies into context instead of bind-llm-client** — will remove the `:guard`-stage `bind-llm-client` indirection and instead inject dependencies (LLM client, embedder, memory backend) directly into the ctx map before the chain starts.
- **[008] Refactor KG-BM25 backend into focused namespaces** — does not change chain order but affects the `:enrich` memory-recall payload.

## 5. Migration phases

### Phase 1 — engine + schema

**Codepaths**

| Path | Change |
|---|---|
| `src/kschltz/agent/chain.clj` | NEW — `execute`, `enqueue`, `terminate`, `::queue`/`::stack`/`::error` machinery, `:chain/instrument?` hook |
| `src/kschltz/agent/interceptors/schema.clj` | NEW — `Ctx` Malli schema, `Interceptor` schema (`[:map [:name :keyword] [:enter {:optional true} fn?] ...]`) |
| `test/kschltz/agent/chain_test.clj` | NEW — engine semantics tests |
| `deps.edn` | unchanged (no new deps — that's the point) |

**Acceptance criteria**
- [ ] `(chain/execute ctx [a b c])` calls `:enter` in `a b c` order and `:leave` in `c b a` order; verified with an event-recording interceptor fixture
- [ ] An interceptor returning `(chain/enqueue ctx [d])` runs `d` after the current queue; `d`'s `:leave` unwinds in correct stack position
- [ ] `(chain/terminate ctx)` skips all remaining `:enter`s; already-entered interceptors still get `:leave`
- [ ] Exception in `:enter` of `b`: `c` never enters; `:error` walked `b → a`; if `a`'s `:error` returns ctx without `::error`, `a`'s `:leave` does NOT re-run (Pedestal semantics) and execution completes
- [ ] Unhandled `::error` at end of stack → `execute` rethrows (ex-info wrapping original, with `:chain/stage` and `:interceptor/name` in ex-data)
- [ ] Engine namespace ≤ ~150 lines, no new entries in `deps.edn`
- [ ] `clojure -M:test` green; no existing test touched

### Phase 2 — extract stages (no cutover)

**Codepaths**

| Path | Change |
|---|---|
| `src/kschltz/agent/llm.clj` | Add `LlmClient` protocol; default impl wraps existing `provider-dispatch`-based `llm/call`; impl fns Malli-instrumented |
| `src/kschltz/agent/tools/web.clj` | Already protocol-shaped (`web-search-client`, `default-http-client`) — expose as `WebSearch` protocol, instrument |
| `src/kschltz/agent/memory/embedding.clj` | Wrap LangChain4j calls in `Embedder` protocol, instrument |
| `src/kschltz/agent/interceptors.clj` | NEW — interceptor defs delegating to existing helpers: `compose-context` → `context/compose-context` + `sanitize-context-messages`; `llm-call` → `LlmClient`; `parse-response` → `loop/parse-tool-calls-native` (make public or move); `execute-tools` → `loop/execute-tool-calls` + `format-tool-results-native` + `truncate-tool-result`; `store-exchange` → `loop/store-exchange`; `update-history` → `loop/history-entries-for-exchange` + `context/cap-history`; `deliver-responses` → `loop/deliver-response`; `notify` → `loop/fire-on-thought` / `fire-memory-event`; `dispatch`, `api-error-retry`, `tool-error-retry`, `wrap-up`, `error-boundary` |
| `src/kschltz/agent/loop.clj` | Only visibility changes (`defn-` → `defn` for reused helpers); behavior untouched |
| `test/kschltz/agent/interceptors_test.clj` | NEW — per-interceptor unit tests on synthetic ctx maps |

**Acceptance criteria**
- [ ] Every interceptor in the §3 inventory exists as a var satisfying the `Interceptor` schema
- [ ] Each interceptor unit-tested with a synthetic ctx and fake protocols — no network, no Datalevin, no real embedder anywhere in these tests
- [ ] `LlmClient`/`WebSearch`/`Embedder` impl fns reject schema-invalid input/output when instrumented (one negative test each)
- [ ] `llm-call` interceptor converts provider exceptions into `:llm/api-error` (never throws); `error-boundary` is the only stage that handles raw throws
- [ ] `dispatch` covered for all four branches: pending tool calls, depth exhausted, blank response, normal finalize
- [ ] Existing test suite still green — `loop.clj` public behavior byte-identical (no call sites changed)

### Phases 3–5 — future work (not implemented / not scheduled)

The parity harness, v1 loop cutover, and full pluginization described in the original plan were not implemented as written. The v2 codebase instead:

- Wrote a clean-slate `kschltz.agent.chain` engine.
- Built `kschltz.agent.plugin`, `kschltz.agent.plugins.base`, and `kschltz.agent.plugins.memory` directly, without first preserving v1 `loop.clj` behavior.
- Kept the tool registry and dispatch separate from the interceptor chain; tools are not yet interceptorized.

The detailed Phase 3–5 acceptance criteria are retained for historical context only. Do not schedule them as-is.

**Not implemented:**
- Phase 3 — parity harness against legacy `loop/llm-turn`.
- Phase 4 — cutover from legacy loop to new chain.
- Phase 5 — pluginization of v1 tools (`remember`, `repl`, `clj-edit`, `web`, `portal`, `safety`).

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Hand-rolled engine bugs (unwind/error edge cases) | Engine is frozen-contract, ~120 lines, exhaustively tested in Phase 1; semantics copied from Pedestal's documented behavior |
| Retry semantics drift (trim rules, shared retry counters) | Phase 3 parity corpus covers every branch of current `llm-turn` cond |
| Callback ordering changes | Parity tests assert event sequences, not just final output |
| Chain mutating the agent mid-exchange | Chain only touches the snapshot; all mutation staged in `:agent/state-delta`, merged by the outer loop |
| Plugin slot explosion / unclear ordering | Fixed, documented slot names; within a slot, plugin declaration order; no priority numbers |
| Instrumentation overhead | Schema checks gated by `:chain/instrument?`, default off in prod |

## Out of scope (deliberately)

- Async/non-blocking execution (engine contract allows adding it later;
  don't build it now)
- Interceptorizing the outer drain/sleep tick — simple and
  concurrency-sensitive; leave it
- Any change to memory/storage behavior or on-disk format
