---
name: kb
description: Kanban board workflow — lane discipline, scope enforcement, and agent bootstrap for kb
trigger: /kb
---

# kb — Agent Workflow Skill

This skill teaches you how to work with the `kb` kanban board. Follow these rules whenever you interact with a kb-managed project.

## Bootstrap Sequence

When you start working in a kb-managed project, follow this sequence:

1. `kb status` — see all lanes and available cards
2. `kb pull --lane <lane> --agent claude` — claim the next card (creates worktree + branch)
3. `kb whoami` — confirm which card you're working on
4. `kb context <id>` — load full card context (lane instructions, gates, rejection warnings)
5. Work within the lane's scope (see Lane Discipline below)
6. `kb heartbeat <id> --doing "description" --progress 0.N` — signal you're alive every ~2 min
7. `kb note <id> "what you completed"` — log progress after significant steps
8. `kb advance <id>` — move to the next lane when lane work is done
9. Repeat from step 4 in the new lane

## Lane Discipline — HARD RULES

These are non-negotiable. Violating them breaks the workflow for the entire team.

### First action in any lane: read the rules

- Before doing ANY work in a lane, run `kb context <id>` and read the MUST/MUST NOT sections
- The lane scope is authoritative — not your intuition about what 'needs doing'
- If you skipped this step and started working blindly, stop and read `kb context <id>` now

### Sequential traversal only

- You MUST advance through lanes one at a time using `kb advance <id>`
- You MUST NOT skip lanes (e.g., jumping from the first lane to the last)
- You MUST NOT use `kb move <id> <lane>` to jump to an arbitrary lane unless the user explicitly approves the destination lane

**Why**: Each lane has a purpose. Skipping lanes means skipping work (no plan, no tests, no review, etc.).

### Stay in lane scope

- Read the lane instructions via `kb context <id>` before starting work in any lane
- The lane instructions contain MUST and MUST NOT sections — follow them exactly
- Every lane restricts what kind of work is allowed. For example:
  - Early lanes (discovery, plan) typically forbid code changes
  - Implementation lanes allow code changes but forbid scope creep
  - Test lanes allow writing/fixing tests but forbid new features
  - Review lanes allow addressing feedback but forbid new work
- When in doubt, re-read `kb context <id>` — the lane instructions are authoritative

**Why**: Mixing lane scopes leads to unplanned work, unreviewed changes, and untested code.

### Common violations that get work rejected

| Violation | What happens | Correct approach |
|-----------|-------------|-----------------|
| Writing code in discovery | Card rejected back to discovery | Research only, then `kb advance` to plan |
| Writing code in plan | Card rejected back to plan | Plan only, then `kb advance` to in-progress |
| Adding features in unit-tests | Card rejected back to unit-tests | Write/fix tests only, then `kb advance` |
| Skipping plan to code | Card rejected back to discovery | Plan first, code second |
| Advancing without a `kb note` | `kb advance` is blocked | Log at least one note per lane before advancing |
| Using `kb move` to skip lanes | Command refused (unless user-approved) | Use `kb advance` for sequential traversal |

### Do not move without completing lane work

- Do not `kb advance` until you have completed the MUST items from the lane instructions
- If the lane says "write your plan as a `kb note` before advancing" — you must have that note
- If gates fail, fix the issue before retrying — do not bypass gates

### Approval-required lanes

- Some lanes have `requires_approval: true` in the board config
- If a lane requires approval, the card will auto-block when you advance into it
- Do not proceed until the human approves via `kb approve <id>`
- Use `kb ask <id> "question"` if you need clarification while waiting

### Confidence and auto-blocking

- If the board has `min_confidence` configured for a lane, provide `--confidence N` (0-1) when advancing
- Low confidence may auto-block the card — this is intentional, not an error
- If blocked, document what's unclear via `kb note` and `kb ask` the human

## What to Do When You're Unsure

1. `kb context <id>` — re-read the full context (lane instructions, gates, rejection history)
2. `kb log <id>` — check for human notes or answers
3. `kb ask <id> "your question"` — ask the human rather than guessing
4. If the question is about skipping a lane or moving out of scope: always ask first, never assume

## Quick Reference

| Command | Description |
|---------|-------------|
| `kb status` | Show all lanes and cards |
| `kb pull [--lane L] [--agent A]` | Claim next card |
| `kb show <id>` | Card details |
| `kb context <id>` | Full card context for agent prompt |
| `kb context <id> --scope` | Lane scope restrictions only (re-inject mid-conversation) |
| `kb whoami` | Which card am I working on? |
| `kb note <id> "msg"` | Log progress |
| `kb ask <id> "question"` | Ask the human (blocks card) |
| `kb advance <id>` | Move to next lane (runs gates) |
| `kb reject <id> --reason R` | Send card back (if lane work failed) |
| `kb block <id> --reason R` | Mark card blocked |
| `kb heartbeat <id>` | Record agent heartbeat |
| `kb diff <id>` | Show diff vs base branch |