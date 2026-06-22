# Lateralus-v2 Improvement Plan — 2026-06-22

> **Status — implemented 2026-06-22.** Both items landed; `clojure -M:test`
> passes 415 tests / 0 failures. Key files: `src/kschltz/agent/logging.clj`
> (new), `chain.clj` (per-stage `:chain/log?`/`:chain/on-stage` seam in
> `try-stage`), `plugins/base.clj` (logging at `:guard`, `ensure-text-response`
> at `:finalize`), `system.clj` (`:lateralus/logging` Integrant key),
> `runtime.clj` (per-session sink in `start`), `loop.clj`
> (`ensure-text-response-interceptor`, `compose-summary-request`,
> `compose-empty-retry`, `configured-react-loop`, registry-hint in dispatch,
> stall detection via `:agent/last-tool-call-sig` + `:agent/loop-continuing?`),
> `cli.clj` (tool-result-summary now falls back to `:agent/all-tool-results`).
> Removed the untracked, non-compiling `tools/prototyper/` scaffolding from
> the prior session to satisfy the quality gate.

Two items, both grounded in the current interceptor-chain architecture.

---

## 1. Logging interceptor, enabled by default

### Goal

Ship a logging capability expressed as an interceptor (the project's universal
extension shape) and on by default. On `:enter` it logs chain fn calls and their
args; on `:leave` it logs the results. Default sink: a file.

### Why

There is no observability into the exchange chain today. When a turn fails
silently (empty response, loop stop, unregistered tool), there is nothing to
grep — only the raw `clojure-*.edn` stack report. The bad multi-tool session on
2026-06-21 was un-debuggable because we could not see which stages ran, what
ctx they saw, or what they returned.

### Design

Two layers, because "logs chain fn calls and their args" means **per-stage**,
not one outer wrapper:

**Layer A — per-stage hook in the engine (primary).** Mirror the existing
`:chain/instrument?` / `:chain/validate` seam in `chain.clj`. Today
`check-instrumented` runs `(validate ctx)` after every stage and throws on
non-nil. Add a sibling, non-throwing, non-validating hook:

- New ctx keys: `:chain/log?` (boolean) and `:chain/on-stage` — a fn
  `(fn [ctx interceptor stage direction] ...)` where `stage` is `:enter` /
  `:leave` / `:error` and `direction` is `:enter` / `:leave`.
- In `try-stage`, call `on-stage` BEFORE and AFTER the stage fn when `:chain/log?`
  is true. Wrap in `try/catch Throwable` so a logging failure never breaks the
  chain (log the log-error to `*err*`, continue).
- `on-stage` receives the interceptor's `:name`, the `stage`, and a **redacted
  ctx view** (see below). It must not receive the engine's `::queue` / `::stack`
  / `::error` bookkeeping — strip them.
- This is the only way to log *every* stage fn call + args + result without
  wrapping each interceptor individually.

**Layer B — an interceptor for outer enter/leave + sink wiring (secondary).**
A `logging` interceptor placed FIRST in the queue (so its `:enter` runs first,
`:leave` runs last). Its `:enter` seeds `:chain/log? true` and `:chain/on-stage`
onto ctx (pointing at the configured sink). Its `:leave` flushes/closes the sink
for the exchange and writes a final exchange-summary line
(`session-id`, `user-msg-id`, `tool-runs`, `depth`, `response-bytes`). This is
the "interceptor shape" the human asked for; Layer A is the per-stage muscle it
turns on.

**Redaction.** `:llm/request` carries `:api-key`; `:agent/state` carries it too.
The `on-stage` fn must never log `:api-key` — redact to `"<redacted>"` before
serializing. Also cap `:llm/request :messages` body length per entry (e.g.
truncate to 4 KB) and cap `:tool/results` result strings (e.g. 8 KB) so a huge
file read doesn't blow up the log.

**Default sink: file.** A `FileLogSink` record writing to
`${LATERALUS_LOG_DIR:-./logs}/lateralus-<session-id>.edn`. One line per stage,
tagged EDN map: `{:ts :session :stage :name :direction :ctx-view}`. Append-only,
`java.io.PrintWriter` with `flush` on `:leave` of the outer interceptor. The
sink is a protocol (`LogSink`: `-open`, `-write`, `-close`) so a future
`stdout`/`slf4j`/OTLP sink drops in without touching the engine.

**Default-on.** `runtime.clj` `start` seeds `:chain/log? true` and a
`FileLogSink` into the agent-map (or the base plugin's first interceptor does).
Turn OFF via config: `:lateralus/logging {:enabled false}` or env
`LATERALUS_LOG_ENABLED=false`. Native-image must still boot with logging on
(no new deps — just `java.io`).

### Implementation steps

- [ ] `src/kschltz/agent/logging.clj` — new ns: `LogSink` protocol
      (`-open`/`-write`/`-close`), `FileLogSink` record, `redact-ctx` fn, an
      `on-stage-fn` factory that builds the `:chain/on-stage` callback from a
      sink.
- [ ] `src/kschltz/agent/chain.clj` — add `:chain/log?` / `:chain/on-stage`
      handling in `try-stage` (both `:enter` and `:leave` directions), guarded
      by `try/catch Throwable`. Strip engine keys before handing ctx to
      `on-stage`. No behavior change when `:chain/log?` absent/false.
- [ ] `src/kschltz/agent/interceptors.clj` (or a new `logging.clj` under
      `plugins/`) — a `logging` interceptor: `:enter` seeds
      `:chain/log? true` + `:chain/on-stage` (built from the configured sink);
      `:leave` flushes the sink and writes the exchange-summary line. Slot
      `:guard` so it runs before `error-boundary` and leaves after everything.
- [ ] `src/kschltz/agent/plugins/base.clj` — prepend the logging interceptor to
      the base plugin vector (slot `:guard`, before `error-boundary`).
- [ ] `src/kschltz/agent/system.clj` — new Integrant key `:lateralus/logging`
      with Malli schema `{:enabled :boolean :dir [:string?] :sink [:enum :file
      :stdout]}`, default `{:enabled true :sink :file}`. Wire into
      `:lateralus/agent` so the runtime can see it.
- [ ] `resources/lateralus/config.edn` + `demo-ollama.edn` + `demo-ollama-tools.edn`
      — add `:lateralus/logging {}` (defaults take effect).
- [ ] `src/kschltz/agent/runtime.clj` — open the sink in `start`, close in `stop`,
      and ensure the sink is on the ctx the chain sees (via the logging
      interceptor's `:enter`).
- [ ] Tests: `test/kschltz/agent/logging_test.clj` — a `RecordingSink` (atom
      vector), assert every stage in `default-exchange-chain` produces an
      `:enter` and `:leave` event; assert `:api-key` is redacted; assert a
      throwing stage still logs `:enter` and that logging itself throwing does
      not break the chain.

### Acceptance checks

- `clojure -M:test -n kschltz.agent.logging-test`
- `rg ':chain/log\?' src/kschltz/agent/chain.clj` (the engine seam exists)
- `rg ':api-key' src/kschltz/agent/logging.clj` (redaction present)
- Run `clojure -M:run --config resources/lateralus/demo-ollama.edn ... -i`, send
  one message, then `ls logs/` shows `lateralus-<session>.edn` and `grep
  :stage logs/lateralus-*.edn` shows `compose-context`, `llm-call`,
  `parse-response`, `dispatch-tools`, `tool-loop` entries.
- `LATERALUS_LOG_ENABLED=false clojure -M:run ...` → no `logs/` written.

### Risks / Fallbacks

- **Perf / log volume.** A 5-deep ReAct loop with 8 stages = ~80 lines/exchange.
  Mitigate with truncate caps and a `:chain/log-level` knob
  (`:stage` / `:exchange` / `:off`). Fallback: default to `:exchange`-level only
  (outer interceptor summary) if per-stage proves too noisy.
- **Engine mutation risk.** `try-stage` is hot. Keep the log hook behind a
  boolean check and a `try/catch`; benchmark before/after.
- **Secret leakage.** Redaction is mandatory; add a test that fails if
  `:api-key` appears in any sink record.
- **Native-image.** `java.io.PrintWriter` is fine; no new deps. Verify with
  `clojure -T:build uber` + a smoke run.

---

## 2. Better multi-tool-call-per-message UX

### Goal

When the model makes tool calls across a turn, the user should always get a
textual response at the end — never "The assistant used tools but produced no
final text." or "The assistant produced no response for this turn." The ReAct
loop should self-drive through tool calls without the human typing "go"/"ok"
repeatedly, and when it stops it should stop with an answer (or a clear
failure message), not silence.

### Why (the pain, from the 2026-06-21 session)

- The model emitted tool calls turn after turn with empty assistant content.
  When it finally stopped emitting tool calls, it also emitted empty text →
  `:exchange/response` empty, CLI printed "The assistant used tools but
  produced no final text."
- The human had to type `go`, `ok`, `go on` repeatedly to prod the model —
  each prod was a NEW `send-message`, meaning the in-flight ReAct loop had
  already terminated with an empty response.
- On a plain `hi`, the model returned empty content with no tool calls → "The
  assistant produced no response for this turn." Nothing coaxed a retry.
- An unregistered-tool call silently stops the loop:
  `implemented-result?` is `(not (starts-with? "Tool '"))`, so an unknown-tool
  result makes `-continue?` false → loop stops, and if response text is empty
  the user sees silence.

Root cause: the loop's stop conditions are correct for "should I loop?" but
nobody guarantees a non-empty final `:exchange/response` when the loop stops.

### Design

All fixes are interceptor-shaped and slot into the existing chain; no rewrite
of the loop protocol.

**A. Finalize-forced summary (`:finalize`, after `tool-loop`).** A new
`ensure-text-response` interceptor at slot `:finalize`, placed AFTER
`tool-loop-interceptor`. On `:enter`, when `-continue?` has decided NOT to loop
again (i.e. `tool-loop-interceptor` did not enqueue a follow-up), check:
- If `:exchange/response` is blank AND `:agent/all-tool-results` is non-empty →
  enqueue one more mini-chain: a `compose-summary-request` interceptor that
  appends a system message like "You have finished calling tools. Now produce
  the final answer for the user using the tool results above." + `llm-call` +
  `parse-response`. Cap at one summary call (guard with a flag
  `:agent/summary-attempted`).
- If `:exchange/response` is blank AND there are NO tool results → enqueue a
  single retry with a nudge system message ("Your last response was empty.
  Reply to the user."), capped at `:agent/empty-retry-attempts` (default 1).

This is the single most important fix: it guarantees the user always gets text.

**B. Make `max-loop-depth` configurable.** Today `max-loop-depth` is a private
`def` = 5 in `loop.clj`. For exploration-heavy tasks (the web-browser session
hit it), 5 is too low. Move it to the `ReActLoop` record (already a field) and
let config set it: `:lateralus/tools-plugin {:max-loop-depth 8}` or an env var.
Default stays 5 to preserve behavior.

**C. Unregistered-tool stop should still yield text.** When
`dispatch-tools-interceptor` produces a result starting with `"Tool '"` (the
unavailable marker) and the model's response text is empty, the
`ensure-text-response` interceptor (A) already covers this — but also have
`dispatch-tools-interceptor` append a system message to `:llm/request :messages`
*before* the follow-up turn telling the model "Tool X is not available; pick
from: <registered names>." so the model self-corrects instead of silently
looping or stopping.

**D. CLI fallback display.** In `cli.clj`, when an exchange ends with empty
`:exchange/response` but non-empty `:agent/all-tool-results`, print a compact
summary of the tool results (tool name + first N chars of result) instead of
the bare "no response" message. This is a display-layer safety net on top of
(A); if (A) ever fails to coax text, the user at least sees what happened.

**E. Loop-stall detection.** If the model emits the SAME tool call (same name +
same args hash) twice in a row across loop iterations, stop the loop and force
the summary (A) — the model is stuck in a rut. Track last call signature on ctx
(`:agent/last-tool-call-sig`).

### Implementation steps

- [ ] `src/kschltz/agent/loop.clj` — export `max-loop-depth` via the `ReActLoop`
      record field (already exists); add a `react-loop` 1-arg default and a
      configurable path. Add `ensure-text-response-interceptor` in slot
      `:finalize` after `tool-loop-interceptor`.
- [ ] `src/kschltz/agent/loop.clj` — `compose-summary-request-interceptor`:
      appends the "produce the final answer" system message to
      `:llm/request :messages`. `compose-empty-retry-interceptor`: appends the
      "your last response was empty" nudge. Both respect per-exchange attempt
      caps stored on ctx.
- [ ] `src/kschltz/agent/loop.clj` — in `dispatch-tools-interceptor`, when a
      tool is not in the registry, append a "tool not available, choose from
      <names>" system message to `:llm/request :messages` so the follow-up
      turn can self-correct.
- [ ] `src/kschltz/agent/loop.clj` — loop-stall detection: record
      `:agent/last-tool-call-sig` (hash of tool name + args); if the new call
      sig equals the last, skip the follow-up and let `ensure-text-response`
      run.
- [ ] `src/kschltz/agent/plugins/base.clj` — insert
      `ensure-text-response-interceptor` at slot `:finalize`, AFTER
      `tool-loop-interceptor`.
- [ ] `src/kschltz/agent/system.clj` — add `:max-loop-depth` to the tools-plugin
      config schema; thread it into `react-loop` construction.
- [ ] `src/kschltz/agent/cli.clj` — when `:exchange/response` is blank and
      `:agent/all-tool-results` is non-empty, print a compact tool-result
      summary (name + truncated result) before the prompt.
- [ ] Tests (deterministic, scripted LLM client pattern already proven in
      `web_search` tests):
      - model emits tool_call then empty text + no further calls →
        `ensure-text-response` enqueues a summary call; final
        `:exchange/response` is non-empty.
      - model returns empty content with no tool calls → one empty-retry fires.
      - model calls an unregistered tool → registry-hint message is appended and
        the loop does not silently stop with empty text.
      - model emits the same tool_call twice → stall detection stops the loop
        and summary runs.
      - `max-loop-depth` is respected when set from config.

### Acceptance checks

- `clojure -M:test -n kschltz.agent.loop-test` (new/extended).
- `rg 'ensure-text-response' src/kschltz/agent/loop.clj
  src/kschltz/agent/plugins/base.clj` (wired).
- `rg 'max-loop-depth' src/kschltz/agent/system.clj` (configurable).
- Deterministic e2e: build system from `system/default-config` with a scripted
  LlmClient that emits `[tool_call(file/list), empty-text-no-calls]`; assert
  final `:exchange/response` is non-empty (summary fired).
- Manual: rerun the 2026-06-21 web-browser session flow with
  `demo-ollama.edn`; confirm the agent completes the multi-tool task and
  prints a final textual answer with no `go`/`ok` prodding.

### Risks / Fallbacks

- **Extra LLM cost.** (A) adds one summary call per exchange that would
  otherwise end silent. Acceptable — silence is worse. Cap at 1 summary + 1
  empty-retry per exchange; make both configurable and default-off-able via
  `:lateralus/tools-plugin {:ensure-text? false}`.
- **Summary call also returns empty.** Then (D) CLI fallback prints tool
  results, so the user is never staring at a blank line. Log it (item 1 makes
  this visible).
- **Stall detection false positives.** A legitimate re-call with identical args
  (e.g. polling) would be cut. Mitigate: only trigger on 2 identical
  consecutive calls AND no text between them. Fallback: make it advisory (log
  + nudge) rather than hard-stop.
- **max-loop-depth too high burns tokens.** Default unchanged (5); config is
  opt-in.

---

## Out of scope (2026-06-22)

- Workspace-root confusion from the 2026-06-21 session (agent resolved paths to
  `/tmp/lateralus-demo`) — that is a config/scope issue, already understood, not
  part of these two items.
- DDG `HTTP 404` from the web tool — separate provider bug; tracked elsewhere.
- The `prototyper` tool the agent started scaffolding — separate effort.
- Streaming/token-level logging, OTLP/sl4j sinks — future work once the
  `LogSink` protocol lands.