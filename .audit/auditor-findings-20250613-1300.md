# Auditor findings — fifth pass (commit e3b9d31 "Audit #4: …")

Session: auditor (25b5ff64-cf32-4d1e-9294-b2f75329ca4f)
Repo: /Users/schltzk/projects/lateralus-v2
Reviewed: ed22b45 + f42e8c5 + b9d07e4 + 4cfb93e + e3b9d31
Working tree: clean.
Test status: **53 tests, 139 assertions, 0 failures, 0 errors** (verified locally).
LOC: ~1555 total, ~743 src, ~812 test (~52% test). Growth from start of audit: 1063 → 1555 (+47%).

## Status of fourth-pass findings

| # | Finding | Status |
|---|---------|--------|
| 1 | DECISION: `chain.clj` `error-map` lets `ex-data` clobber engine metadata | **APPLIED.** New `reserved` set `#{:exception :interceptor/name :chain/stage}` stripped from `ex-data` via `apply dissoc` before the merge. Engine keys are now guaranteed to win. New test `error-map-preserves-engine-stage-over-ex-data` pins the behavior. |
| 2 | POLISH: `notify` def indentation bug | **APPLIED.** `:name` and `:leave` now aligned. |
| 3 | POLISH: dead imports `[integrant.core :as ig]` and `[kschltz.agent.system :as lateralus-system]` | **APPLIED.** Both removed. |
| 4 | POLISH: misleading `full-exchange-with-agent-client` test description and `:embedder`/`:memory-backend` type-misuse | **APPLIED.** Renamed to `agent-map-client-flows-into-exchange`; testing string now says "agent-map-configured"; `:embedder` and `:memory-backend` use `:placeholder` instead of `(lcm-client/stub-client)`. |
| 5 | POLISH: `bind-llm-client` docstring doesn't say "ctx wins over agent" | **APPLIED.** New paragraph in docstring: "Ctx-precedence: if `:llm/client` is already on ctx (a plugin or test set it explicitly), the ctx value wins — `bind-llm-client` is a *fallback* binding, not an override. See `bind-llm-client-prefers-ctx-client` in the test file." |
| 6 | POLISH: `build-interceptor` test for non-map `ix` shape is missing | **APPLIED.** New `(map? ix)` guard throws `ex-info "Plugin slot interceptor must be a map"` with `:hint`. New `assemble-chain-rejects-non-map-ix` test covers `nil`, `"string"`, `:keyword`, `[1 2 3]`, `42`. |
| 7 | POLISH: hardcoded `:kschltz.agent.chain/error` keyword literal in test | **APPLIED.** Test now uses `::chain/error` resolved through the existing `kschltz.agent.chain :as chain` import. |

All 7 fourth-pass findings properly addressed. The codebase is now in **the cleanest state it has been across all five audit passes**.

## New issues found in this pass (3 items, all POLISH or pre-existing documentation)

### 1. (POLISH) `error-map` docstring says "select-keys" but the code uses `dissoc`

File: `src/kschltz/agent/chain.clj:55-75`
Literal (docstring):
> "select-keys limits the bleed to non-reserved ex-data keys."

Literal (code):
```clojure
reserved #{:exception :interceptor/name :chain/stage}
ex-data-clean (when ex-data
                (apply dissoc ex-data reserved))
```

The docstring says `select-keys`; the code does `dissoc`. Both achieve the same end-state here (reserved keys are absent from `ex-data-clean`), but they have different implications:
- `dissoc`: forwards-compatible — any new ex-data key passes through. Engine keys are protected.
- `select-keys`: backwards-compatible whitelist — only listed keys pass through. Anything else is silently dropped.

The current `dissoc` is the correct choice (forwards-compat) — adding a new ex-data key should "just work" without a schema update. The docstring should match.

Fix: change "select-keys limits the bleed" to "dissoc of the reserved set limits the bleed — any other ex-data key flows through unchanged."

### 2. (POLISH) `error-map-preserves-engine-stage-over-ex-data` test covers 1 of 3 reserved keys

File: `test/kschltz/agent/chain_test.clj:158-175`
The test pins `:chain/stage` collision but not `:exception` or `:interceptor/name`. The contract is symmetric across the three reserved keys, so the test demonstrates the mechanism — but a regression that broke the `:exception` or `:interceptor/name` protection specifically would not be caught.

Fix: a `doseq` over the three keys (or three sub-tests). Sketch:
```clojure
(deftest error-map-preserves-engine-keys-over-ex-data
  (testing "engine metadata wins over ex-data for every reserved key"
    (doseq [reserved-key [:exception :interceptor/name :chain/stage]
            :let [bomb (ex-info "boom" {reserved-key :user-stuff
                                        :where      :user-claim})]]
      (try
        (chain/execute {} [(recorder :a :enter (fn [_] (throw bomb)))])
        (is false "expected throw")
        (catch clojure.lang.ExceptionInfo e
          (let [d (ex-data e)]
            (is (not= :user-stuff (get d reserved-key))
                (str "user's " reserved-key " is stripped; engine's wins"))
            (is (= :user-claim (:where d))
                "non-reserved ex-data still flows through"))))))))
```
This is a 10-line test that pins all three keys. Worth adding.

### 3. (POLISH, pre-existing) `docs/interceptor-loop-implementation-plan.md` is stale

File: `docs/interceptor-loop-implementation-plan.md:168, 219`
The doc still references v1 stages that don't exist in v2: `api-error-retry`, `tool-error-retry`, `wrap-up`. These were in v1's `loop/` namespace and were deliberately not ported. The plan doc was written before the v2 rewrite and hasn't been updated.

This is a documentation drift issue, not a code issue. The code is correct (it does not have those stages). But a future reader of the plan doc will be confused why the v2 implementation "missed" those stages. Either:
- (a) Add a one-line note in the plan doc that these v1 stages were deliberately dropped in v2 (with the reason — they were loop-coupled; v2 puts retry/tool-error handling in plugin slots).
- (b) Open a tracking issue to refresh the plan doc when Step 5/6 land.

(a) is one line. Recommended.

## What I did NOT find

- `rg 'agent\.loop' src/` — clean.
- `rg 'add-.*-tool!' src/` — clean.
- `rg 'pmap' src/` — clean.
- `rg 'http/completion' src/` — clean.
- `rg 'TODO' src/ test/` — only the two intentional Step 6 markers.
- All tests pass. No test pollution. No broken test premises. No dead imports. No misaligned indentation. No test docstring lies.
- The forbidden patterns remain clean.

## Standing down

I will not edit `src/kschltz/agent/**` or `test/kschltz/agent/**` without flagging first.

**Closing note for the audit series:** we've now done five passes. The v2 Steps 2-4 work is solid:
- chain engine: well-tested, contract documented, no leftover v1 coupling
- plugin system: open-schema contract explicit, validation surfaces real Malli problems, all-nil and non-map interceptors rejected
- default exchange chain: stages in correct order, integrations tested end-to-end
- Integrant system: halt policy correct, agent init shape stable, no test pollution
- audit fixes (4 passes): the BLOCKERs and DECISIONs from each pass were applied correctly; the new code is well-tested and the test descriptions match what the tests actually do

Items 1-3 above are real but minor. Item 1 is a one-line docstring fix. Item 2 is a 10-line test. Item 3 is a one-line plan-doc note. None block Step 5 (LlmClient HTTP) or Step 6 (memory).

The audit loop is reaching diminishing returns. Recommend either (a) ship Steps 5/6 and audit after each, or (b) close out the Steps 2-4 milestone and revisit when there's new code to review. I'll stand down unless explicitly asked for another pass on the same code.