#!/usr/bin/env bash
# One-shot: Lateralus workbench against a running llama.cpp / MLX server.
# MCP servers are empty at boot — the agent attaches them via mcp_upsert_server
# (Open Design tools-dev IPC recipe is in that tool's description).
#
# Assumes:
#   - OpenAI-compatible LLM server already listening (default http://127.0.0.1:8888/v1)
#   - Optional: Open Design tools-dev IPC or HTTP daemon (agent can connect later)
#
# Usage:
#   ./scripts/start-llama-workbench.sh              # interactive model pick
#   ./scripts/start-llama-workbench.sh MODEL_ID     # skip picker
#   LATERALUS_MODEL=… ./scripts/start-llama-workbench.sh
#
# Env:
#   LLAMA_BASE_URL / LATERALUS_BASE_URL   default http://127.0.0.1:8888/v1
#   LATERALUS_MODEL                       skip picker when set
#   LATERALUS_WORKBENCH_PORT              default 7860 (auto-bumps if busy)
#   OPEN_BROWSER=0                        skip opening the UI
#   START_OD=1                            start HTTP OD daemon if neither HTTP nor IPC is up
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BASE_URL="${LLAMA_BASE_URL:-${LATERALUS_BASE_URL:-http://127.0.0.1:8888/v1}}"
BASE_URL="${BASE_URL%/}"
WB_PORT="${LATERALUS_WORKBENCH_PORT:-7860}"
OPEN_BROWSER="${OPEN_BROWSER:-1}"
START_OD="${START_OD:-0}"
CONFIG_SRC="${CONFIG:-resources/lateralus/llama-workbench.edn}"
OD_DAEMON_URL="${OD_DAEMON_URL:-http://127.0.0.1:7456}"
OD_SIDECAR_IPC_PATH="${OD_SIDECAR_IPC_PATH:-/tmp/open-design/ipc/default/daemon.sock}"
MODEL="${LATERALUS_MODEL:-${1:-}}"

log() { printf '==> %s\n' "$*" >&2; }
err() { printf 'error: %s\n' "$*" >&2; }

open_browser() {
  local url="$1"
  case "$(uname -s 2>/dev/null || echo unknown)" in
    Darwin) command -v open >/dev/null && open "$url" || true ;;
    *)      command -v xdg-open >/dev/null && xdg-open "$url" || true ;;
  esac
}

port_free() {
  local p="$1"
  if command -v lsof >/dev/null 2>&1; then
    ! lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1
  else
    ! curl -sf --max-time 0.2 "http://127.0.0.1:${p}/" >/dev/null 2>&1
  fi
}

pick_free_port() {
  local p="$1" i
  for i in $(seq 0 20); do
    if port_free "$((p + i))"; then
      echo "$((p + i))"
      return 0
    fi
  done
  err "no free workbench port near ${p}"
  exit 1
}

llama_ok() {
  curl -sf --max-time 2 "${BASE_URL}/models" >/dev/null 2>&1
}

od_http_ok() {
  curl -sf --max-time 1 "${OD_DAEMON_URL}/health" >/dev/null 2>&1 \
    || curl -sf --max-time 1 "${OD_DAEMON_URL}/" >/dev/null 2>&1
}

od_ipc_ok() {
  [[ -S "${OD_SIDECAR_IPC_PATH}" ]]
}

list_models() {
  local body
  body="$(curl -sf --max-time 5 "${BASE_URL}/models" || true)"
  [[ -n "$body" ]] || return 1
  if command -v python3 >/dev/null 2>&1; then
    printf '%s' "$body" | python3 -c '
import json,sys
d=json.load(sys.stdin)
items=d.get("data") or d.get("models") or []
loaded=[]
rest=[]
for m in items:
    mid=m.get("id") or m.get("name") or ""
    if not mid: continue
    st=(m.get("status") or {})
    val=st.get("value") if isinstance(st, dict) else None
    (loaded if val=="loaded" else rest).append(mid)
for mid in loaded+rest:
    print(mid)
'
  else
    printf '%s' "$body" | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
  fi
}

pick_model() {
  local models=() line i choice default
  while IFS= read -r line; do
    [[ -n "$line" ]] && models+=("$line")
  done < <(list_models || true)

  if [[ ${#models[@]} -eq 0 ]]; then
    err "no models reported by ${BASE_URL}/models"
    exit 1
  fi

  default="${models[0]}"
  if [[ ! -t 0 ]]; then
    echo "$default"
    return 0
  fi

  echo "" >&2
  log "models at ${BASE_URL} (pick one; switch later via list_llm_models / set_llm_config):"
  for i in "${!models[@]}"; do
    printf '  %2d) %s\n' "$((i + 1))" "${models[$i]}" >&2
  done
  printf 'Model [1=%s]: ' "$default" >&2
  read -r choice || true
  if [[ -z "${choice}" ]]; then
    echo "$default"
    return 0
  fi
  if [[ "$choice" =~ ^[0-9]+$ ]] && (( choice >= 1 && choice <= ${#models[@]} )); then
    echo "${models[$((choice - 1))]}"
    return 0
  fi
  echo "$choice"
}

# --- main -------------------------------------------------------------------

if ! command -v clojure >/dev/null 2>&1; then
  err "clojure not on PATH"
  exit 1
fi

if [[ ! -f "$CONFIG_SRC" ]]; then
  err "config not found: ${CONFIG_SRC}"
  exit 1
fi

if ! llama_ok; then
  err "llama server not reachable at ${BASE_URL}/models"
  err "start llama.cpp (OpenAI-compatible) first, e.g. --port 8888"
  exit 1
fi
log "llama ok at ${BASE_URL}"

if od_http_ok; then
  log "Open Design HTTP daemon up at ${OD_DAEMON_URL}"
elif od_ipc_ok; then
  log "Open Design tools-dev IPC up at ${OD_SIDECAR_IPC_PATH}"
elif [[ "$START_OD" == "1" ]]; then
  log "starting Open Design HTTP daemon at ${OD_DAEMON_URL}"
  OD_DAEMON_URL="$OD_DAEMON_URL" "$ROOT/scripts/start-open-design-daemon.sh" \
    >/tmp/lateralus-od-daemon.log 2>&1 &
  for _ in $(seq 1 30); do
    od_http_ok && break
    sleep 1
  done
  if od_http_ok; then
    log "Open Design daemon ready"
  else
    log "Open Design daemon failed to start (see /tmp/lateralus-od-daemon.log); continuing — agent can mcp_upsert_server later"
  fi
else
  log "Open Design not detected (no ${OD_DAEMON_URL}, no ${OD_SIDECAR_IPC_PATH}); continuing — agent can mcp_upsert_server later"
fi

if [[ -z "$MODEL" ]]; then
  MODEL="$(pick_model)"
fi
log "model=${MODEL}"

WB_PORT="$(pick_free_port "$WB_PORT")"
log "workbench port=${WB_PORT}"

CONFIG_TMP="$(mktemp -t lateralus-llama-wb.XXXXXX.edn)"
cleanup() { rm -f "$CONFIG_TMP"; }
trap cleanup EXIT INT TERM

python3 - "$CONFIG_SRC" "$CONFIG_TMP" "$WB_PORT" "$BASE_URL" "$ROOT" <<'PY'
import sys, re
src, dst, port, base, root = sys.argv[1:6]
text = open(src).read()
text = re.sub(r'(:port\s+)\d+', r'\g<1>' + port, text, count=1)
text = re.sub(r'(:base-url\s+)"[^"]*"', r'\1"' + base + '"', text)
text = re.sub(r'(:workspace-root\s+)"[^"]*"', r'\1"' + root + '"', text)
open(dst, "w").write(text)
PY

if [[ "$OPEN_BROWSER" == "1" ]]; then
  ( sleep 3 && open_browser "http://127.0.0.1:${WB_PORT}" ) >/dev/null 2>&1 &
fi

log "starting Lateralus workbench"
log "  config=${CONFIG_SRC} (port/url baked → temp)"
log "  model=${MODEL}"
log "  UI: http://127.0.0.1:${WB_PORT}"
log "  MCP: open-design tools-dev IPC at boot; agent can mcp_upsert_server to change"
log "  switch models anytime: list_llm_models → set_llm_config"
echo ""

# Only export a live HTTP daemon URL. A dead OD_DAEMON_URL is inherited by
# stdio MCP children and makes Open Design ignore tools-dev IPC.
export OD_SIDECAR_IPC_PATH
export OD_DATA_DIR="${OD_DATA_DIR:-/Users/schltzk/projects/open-design/.od}"
if od_http_ok; then
  export OD_DAEMON_URL
else
  unset OD_DAEMON_URL
fi

# Don't exec: keep the EXIT trap so the temp config is removed after quit.
clojure -M:workbench:run -i \
  --config "$CONFIG_TMP" \
  --base-url "$BASE_URL" \
  --model "$MODEL"
