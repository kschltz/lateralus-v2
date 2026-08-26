#!/usr/bin/env bash
# Local-model self-update smoke: add a tiny lib, require it, confirm loaded?
#
# Assumes an OpenAI-compatible server is already up (llama.cpp / ds4 / Ollama).
#
# Usage:
#   ./scripts/verify-local-self-update.sh
#   LLAMA_BASE_URL=http://127.0.0.1:8888/v1 LATERALUS_MODEL=my-model \
#     ./scripts/verify-local-self-update.sh
#
# Exit 2 if the server is down (skip). Exit 1 if the run failed the rubric.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BASE_URL="${LLAMA_BASE_URL:-${LATERALUS_BASE_URL:-http://127.0.0.1:8888/v1}}"
BASE_URL="${BASE_URL%/}"
MODEL="${LATERALUS_MODEL:-${1:-}}"
LOG="${VERIFY_LOG:-logs/verify-local-self-update.log}"
CONFIG_SRC="${CONFIG:-resources/lateralus/verify-local.edn}"

log() { printf '==> %s\n' "$*" >&2; }
err() { printf 'error: %s\n' "$*" >&2; }

if ! curl -sf --max-time 2 "${BASE_URL}/models" >/dev/null 2>&1; then
  err "local LLM not reachable at ${BASE_URL}/models — start llama.cpp/ds4/Ollama first"
  exit 2
fi

if [[ -z "$MODEL" ]]; then
  MODEL="$(curl -sf --max-time 5 "${BASE_URL}/models" \
    | python3 -c 'import json,sys
d=json.load(sys.stdin)
items=d.get("data") or d.get("models") or []
print((items[0].get("id") or items[0].get("name")) if items else "")' \
    || true)"
fi
if [[ -z "$MODEL" ]]; then
  err "no model id from ${BASE_URL}/models and LATERALUS_MODEL unset"
  exit 2
fi

mkdir -p "$(dirname "$LOG")" logs
STAMP="$(mktemp)"
touch "$STAMP"
PROMPT='Call clojure_add_lib with lib="babashka/fs" and require="babashka.fs". Do not pass coords or version. Then clojure_eval with code="(babashka.fs/cwd)". Final reply must include {"loaded?":true,"cwd":"..."}.'

log "model=${MODEL} url=${BASE_URL}"
log "log=${LOG}"

set +e
clojure -M:run --config "$CONFIG_SRC" \
  --base-url "$BASE_URL" --model "$MODEL" \
  "$PROMPT" >"$LOG" 2>&1
rc=$?
set -e

session_logs="$(find logs -name 'lateralus-*.edn' -newer "$STAMP" 2>/dev/null || true)"
rm -f "$STAMP"

# Score stdout or this run's chain session log (models often paraphrase JSON).
if grep -Eqi '"loaded\?"[[:space:]]*:[[:space:]]*true|loaded\?[[:space:]]*[:=][[:space:]]*`?true' "$LOG" \
  || { [[ -n "$session_logs" ]] && grep -Eql '"loaded\?":true|:loaded\? true' $session_logs; }; then
  log "PASS: loaded?=true appeared in output or session log"
  exit 0
fi

if grep -qi 'did not produce a final answer\|summary-synthesized\|babashka' "$LOG"; then
  log "PARTIAL: run finished but loaded?=true was not seen (see $LOG)"
  exit 1
fi

err "FAIL: no self-update signal in $LOG (exit ${rc})"
exit 1
