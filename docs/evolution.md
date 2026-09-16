# Bounded self-evolution

Lateralus can run one unattended implementation candidate in an isolated Git
worktree, verify it independently, and hand a passing local commit to a human
review lane. It never pushes, merges to the base branch, deploys, completes a
kanban card, or gives the model an unrestricted shell.

## Trust model

The LLM is the implementation worker, not the controller. A deterministic
supervisor owns phase transitions, budgets, changed-path policy, process
execution, Git metadata, verification, the audit ledger, and the final handoff.
Candidate tools are rebound to the candidate worktree and session-disabled
from changing their workspace, permissions, model, MCP servers, runtime
dependencies, secrets, or evolution policy.

The initial implementation has these phases:

```
observe -> propose -> baseline -> isolate -> implement -> verify -> handoff
                                                         \-> rejected/blocked
```

Every candidate uses a new `evolve/*` branch and worktree. Required regression
gates compare against the baseline, so a pre-existing failure is not counted
as a new regression. Required acceptance gates must pass after the edit.
Acceptance fixtures can declare `:protected-paths`; changes to those paths are
rejected before verification. Passing changes are committed locally and
cherry-picked onto the card's own worktree branch so `kb diff` and Review show
the actual candidate.

## Process and network boundary

`CommandRunner` accepts argv vectors only. The operator allowlists programs,
working roots, environment keys, timeout, and output size. No shell parser is
involved. On macOS, commands marked `:network :deny` are wrapped with
`sandbox-exec` and the supervisor rejects them when strict network isolation
is required but unavailable. The process, board, workspace, evaluator,
candidate, and ledger capabilities are protocols; leaf implementations and
constructors are Malli-instrumented.

The candidate's LLM traffic still uses the existing `LlmClient` protocol.
Verification defaults to lint and the fast Clojure test runner inside an OS
sandbox that denies network access and writes outside the worktree. The
supervisor supplies a precomputed, worktree-rebased classpath, so candidate
code cannot make the dependency resolver modify the operator's caches.

## Run locally

Create or claim a `kb` card so it has its own worktree, then write an EDN spec
containing optional `:proposal` / `:policy` and at least one
machine-checkable `:acceptance` gate. When `:proposal` is omitted, the first
failing required acceptance gate is converted into an evidence-backed
self-diagnosed proposal. See
`resources/lateralus/evolution-example.edn` for the complete closed shape.

```bash
clojure -M:evolve \
  --proposal resources/lateralus/evolution-example.edn \
  --card 011 \
  --repo . \
  --config resources/lateralus/demo-ollama.edn
```

The command exits:

- `0` after a passing candidate is committed to the card branch and advanced
  to human Review.
- `2` when the candidate is safely rejected or blocked.
- `1` for invalid configuration or supervisor startup failure.

Audit events are append-only in `.lateralus/evolution.duckdb`. Rejected
candidate worktrees and branches are removed. Passing temporary candidates are
also removed after their verified commit is copied to the card worktree.

## Deliberate limits

- One candidate per invocation; `:max-candidates` reserves the contract for a
  later best-of-N controller.
- Self-diagnosis is gate-driven, not a free-running daemon. An operator or
  scheduler launches the one-shot command; only a reproducible failing gate
  can create autonomous work.
- The evolution kernel, verifier policy, declared holdout paths, Git metadata,
  secrets, and `.lateralus` state are outside candidate write authority.
- Semantic quality is only as strong as the declared acceptance gates. A
  passing build alone is not evidence that an open-ended objective is true.
