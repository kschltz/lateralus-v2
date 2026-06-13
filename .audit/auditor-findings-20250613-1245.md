# Auditor findings — fourth pass (commits b9d07e4 + 4cfb93e)

Session: auditor (25b5ff64-cf32-4d1e-9294-b2f75329ca4f)
Repo: /Users/schltzk/projects/lateralus-v2
Reviewed: ed22b45 + f42e8c5 + b9d07e4 ("Audit #2") + 4cfb93e ("Audit #3 polish")
Working tree: clean.
Test status: **51 tests, 116 assertions, 0 failures, 0 errors** (verified locally).

## Status of third-pass findings

| # | Finding | Status |
|---|---------|--------|
| 1 | BLOCKER: test pollution via `remove-method` for `:lateralus/llm-client` | **FIXED in b9d07e4.** `full-exchange-with-agent-client` rewritten to construct the agent map directly (`{:agent/llm-client marker ...}`) and run the chain against it. No `defmethod` / `remove-method` dance. The multimethod is never touched in tests now. |
| 2 | BLOCKER: `plugin-schema-does-not-recognize-register` test premise wrong | **FIXED in b9d07e4.** The broken test was removed; replaced with `plugin-register-fn-is-not-invoked` which tests the actual behavior (`:plugin/register` is silently ignored by the open schema, fn is never called). |
| 3 | POLISH: `bind-llm-client` always assoc's `:llm/client` to nil | **FIXED in 4cfb93e.** Now `if-let`; only assoc's when a client is found. `call-llm`'s `or` fallback handles the no-client case. |
| 4 | POLISH: `Plugin` schema open-ness is a real risk, not hypothetical | **DOCUMENTED in 4cfb93e.** The `Plugin` docstring now explicitly documents the `{:closed false}` contract: "Extra keys are silently ignored by Malli validation. Use `m/explain` directly if you need to assert specific key presence. … The cost is that removing a plugin key from this schema does not by itself reject legacy plugins that still use it — code that consumes `:plugin/register` etc. must check at runtime, not at validation time." This is the correct resolution — explicit contract over implicit constraint. |
| 5 | POLISH: `bind-llm-client` docstring could note `:http` impl throws at init | **FIXED in 4cfb93e.** Docstring adds: "MVP note: only `:stub` impl is wired in `kschltz.agent.llm.client`; the `:http` impl throws at init time and the chain cannot recover from this until Step 5 lands." |
| 6 | POLISH: `:plugin/original-name` silently nil if input has no `:name` | **FIXED in 4cfb93e.** `build-interceptor` now uses `cond->` to emit `:plugin/original-name` only when present. New test `assembled-interceptor-omits-original-name-when-absent` pins the absent-not-nil behavior. |
| 7 | POLISH: `:plugin/register` schema removal is silent for legacy plugins | **ADDRESSED in 4cfb93e.** Commit message notes the open-schema contract. As you observed in the commit body, this is "a non-issue in practice" for greenfield v2. The new `plugin-register-fn-is-not-invoked` test also serves as a runtime guard against anyone re-introducing reliance on `:plugin/register`. |

All 7 third-pass findings addressed. The codebase is now in a healthy state: tests pass, no test pollution, no broken test premises, contracts are documented.

## New issues found in this pass

I went looking for issues to flag, with the goal of being honest rather than productive. The new state is largely clean. The items below are real but minor.

### 1. (DECISION) `chain.clj` `error-map` lets `ex-data` clobber engine metadata — pre-existing latent bug

File: `src/kschltz/agent/chain.clj:48-56`
Literal:
```clojure
(defn- error-map
  [t interceptor stage]
  (let [ex-data (when (instance? clojure.lang.IExceptionInfo t) (ex-data t))]
    (cond-> {:exception        t
             :interceptor/name (:name interceptor)
             :chain/stage      stage}
      ex-data (merge ex-data))))
```
When the thrown exception is an `IExceptionInfo` (i.e. anything from `ex-info`), the merge happens *after* the engine sets its own keys. If a user's `:enter` throws `(throw (ex-info "boom" {:chain/stage :my-marker}))`, the engine builds `{:exception boom-ex :interceptor/name :foo :chain/stage :enter}` and then merges `{:chain/stage :my-marker}` on top, overwriting the engine's stage. The chain rethrows with `:chain/stage :my-marker`, which then propagates through `try-error` and the error walk. The engine loses its own bookkeeping for the original error site.

This is pre-existing (it was in the v1 port that `chain.clj` is based on), not introduced by the recent changes. Not blocking for MVP. But worth knowing for Step 7 (the outer runtime loop, which will read `:chain/stage` for telemetry and audit trails).

Fix: use `select-keys` on `ex-data` to limit which keys merge, or merge with the engine keys explicitly taking precedence (swap the merge order and use a `merge` that prefers left). Example:
```clojure
(cond-> {:exception t
         :interceptor/name (:name interceptor)
         :chain/stage stage}
  ex-data (merge (select-keys ex-data [:where :hint :whatever])))
```
Or, more defensively, only merge keys that don't collide:
```clojure
(merge (when ex-data (apply dissoc ex-data (keys base))) base)
```
This is a v1 port cleanup; flag for Step 7.

### 2. (POLISH) `notify` def in `interceptors.clj` has misaligned indentation

File: `src/kschltz/agent/interceptors.clj:198-205`
Literal (note the second `:leave` key, one space to the left of `:name`):
```clojure
(def notify
  "Leave stage. Final hook for listeners (UI, telemetry, etc.)."
  {:name ::notify
  :leave (fn [ctx]      ;; <-- one space too few; should be aligned with :name
            (update ctx :exchange/notified
                    (fnil conj [])
                    {:session-id (:exchange/session-id ctx)
                     :event      :complete}))})
```
Cosmetic but visible. Introduced by a previous refactor (probably the f42e8c5 docstring touch-up). Lint tools will flag it. One-line fix:
```clojure
(def notify
  "Leave stage. ..."
  {:name  ::notify
   :leave (fn [ctx] ...)})
```

### 3. (POLISH) `exchange_test.clj` has two dead imports from the previous test-pollution attempt

File: `test/kschltz/agent/exchange_test.clj:16, 23`
Literal:
```clojure
(:require [clojure.test :refer [deftest is testing]]
          ...
          [integrant.core :as ig]            ;; <-- dead
          ...
          [kschltz.agent.llm.client :as lcm-client]
          [kschltz.agent.system :as lateralus-system]   ;; <-- dead
          ...)
```
The `full-exchange-with-agent-client` test was rewritten in b9d07e4 to use direct map construction (no Integrant call), but the `ig` and `lateralus-system` imports from the previous version were not removed. Neither symbol is referenced in the test file body anymore. Dead imports lint as warnings and confuse readers about what the test depends on.

Fix: remove both imports.

### 4. (POLISH) `full-exchange-with-agent-client` description and embedding field types are misleading

File: `test/kschltz/agent/exchange_test.clj:207-235`
Two small confusions:
- The `testing` string says "Integrant-configured agent's LlmClient is what the chain uses" — but the test **does not use Integrant**. The marker client is built and put directly on the agent map. The test exercises the chain's consumption of the agent map, not Integrant. The docstring at lines 212-214 acknowledges this ("no Integrant defmethod override needed"), but the top-line `testing` string is wrong. Suggested: rename to `agent-map-client-flows-into-exchange` or similar, and update the testing string to say "agent-map-configured".
- The agent map uses `(lcm-client/stub-client)` for both `:embedder` and `:memory-backend`. `lcm-client/stub-client` returns a `LlmClient`, not an `Embedder` or `MemoryBackend`. The chain doesn't actually call these (they're on the agent map, not on ctx, and `chain/execute` only reads ctx), so the test passes, but the type-misuse is a footgun for a future reader. The inline comment says `; any value` which is honest, but using literal non-client values like `nil` or `:stub` would be clearer. The marker comment is good as-is; the embedding field is a minor smell.

### 5. (POLISH) `bind-llm-client` docstring doesn't state the ctx-precedence contract explicitly

File: `src/kschltz/agent/interceptors.clj:75-99`
The implementation does `(or (:llm/client ctx) (:agent/llm-client ctx))` — ctx wins. The test `bind-llm-client-prefers-ctx-client` pins this. But the docstring says "Copy the agent's LlmClient from `:agent/llm-client` (set by the runtime per exchange) onto ctx as `:llm/client`" — implying the agent is the source, not the ctx. A future reader who reads the docstring first will be surprised by the test assertion.

Suggested one-liner addition to the docstring: "If `:llm/client` is already on ctx (a plugin or test set it explicitly), the ctx value wins — `bind-llm-client` is a *fallback* binding, not an override. See `bind-llm-client-prefers-ctx-client` in the test file."

### 6. (POLISH) `assemble-chain` test for non-map `ix` shape is missing

File: `src/kschltz/agent/plugin.clj:71-89`
`build-interceptor` calls `(:enter ix)`, `(:leave ix)`, `(:error ix)`, and `(:name ix)`. If `ix` is `nil` (e.g. `{:plugin/slots {:guard [nil]}}`) or a non-map value (e.g. `{:plugin/slots {:guard [:not-a-map]}}`), these all return `nil` and the all-nil check in `build-interceptor` fires with `:interceptor nil` or `:interceptor :not-a-map` in ex-data. The test `assemble-chain-rejects-all-nil-stages` only covers the `ix` is a map case.

This is a minor robustness gap. The behavior is "throw, with a hint, with `ix` in ex-data" — useful for debugging. But a defensive check at the top of `build-interceptor` would make the contract explicit:
```clojure
(when-not (map? ix)
  (throw (ex-info "Plugin slot interceptor must be a map"
                  {:plugin/name plugin-name
                   :plugin/slot slot
                   :interceptor ix})))
```
Either pin the current "throw-by-accident" behavior with a test, or add the explicit guard. Currently it's a hole.

### 7. (POLISH) `:kschltz.agent.chain/error` hardcoded keyword literal in test (carried from 2nd audit, not fixed)

File: `test/kschltz/agent/exchange_test.clj:178`
Literal:
```clojure
(is (not (contains? out :kschltz.agent.chain/error))
    "error-boundary cleared engine ::error so chain doesn't rethrow")
```
This was flagged in my second audit and not addressed in the subsequent commits. The keyword is the engine's internal `::chain/error` resolved in `kschltz.agent.chain` (not in the test ns). A `::chain/error` reader macro in the test ns would expand to `kschltz.agent.exchange-test/chain/error` and silently test the wrong key. The hardcoded string is correct but anti-idiomatic.

The fix is one line: import the engine ns with an alias and use `::chain/error`:
```clojure
(:require ...
          [kschltz.agent.chain :as chain])
;; ...
(is (not (contains? out ::chain/error))
    "error-boundary cleared engine ::error so chain doesn't rethrow")
```
Where the import is already present (the test file already requires `kschltz.agent.chain :as chain`). The keyword was just hardcoded instead of using the alias. This is a small but persistent footgun.

## What I did NOT find

- `rg 'agent\.loop' src/` — clean.
- `rg 'add-.*-tool!' src/` — clean.
- `rg 'pmap' src/` — clean.
- `rg 'http/completion' src/` — clean.
- `rg 'TODO' src/ test/` — only the two intentional Step 6 markers, both clearly labeled.
- No test pollution, no broken test premises, no missing contract documentation.
- The forbidden patterns are still clean.

## Standing down

I will not edit `src/kschltz/agent/**` or `test/kschltz/agent/**` without flagging first. The codebase is in good shape for the next plan step (Step 5 — LlmClient HTTP boundary, or Step 6 — memory). Items 1-7 above are all minor polish; the only one I'd call out as worth a follow-up before Step 5 lands is **#1 (the `error-map` `ex-data` clobber)** — that one is a v1-port latent bug that will interact with the runtime's audit/telemetry once Step 7 lands. The rest are quality-of-life fixes that can wait for any subsequent cleanup pass.
