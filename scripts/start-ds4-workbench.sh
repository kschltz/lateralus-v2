#!/usr/bin/env bash
# One-shot: ds4-server (DeepSeek V4 Flash 0731) + Open Design daemon +
# Lateralus workbench with full tooling (see resources/lateralus/ds4-workbench.edn).
#
# Usage:
#   ./scripts/start-ds4-workbench.sh
#
# Env overrides (defaults match this machine's prior ds4-server run):
#   DS4_DIR / DS4_BIN / DS4_MODEL / DS4_PORT / DS4_CTX / DS4_KV_DIR / DS4_KV_MB
#   DS4_DSPARK=1          enable community DSpark MTP head (off by default)
#   DS4_EXTRA_ARGS        extra flags appended to ds4-server
#   LATERALUS_MODEL       default deepseek-v4-flash
#   LATERALUS_WORKBENCH_PORT  default 7860
#   START_OD=0            skip Open Design daemon
#   START_DS4=0           assume ds4-server already up
#   OPEN_BROWSER=0        skip opening the workbench URL
#   STOP_DS4=1            kill ds4-server we started on exit (default: leave up)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DS4_DIR="${DS4_DIR:-$HOME/projects/ds4}"
DS4_BIN="${DS4_BIN:-$DS4_DIR/ds4-server}"
DS4_MODEL="${DS4_MODEL:-$DS4_DIR/gguf/ds4flash-0731.gguf}"
DS4_MTP="${DS4_MTP:-$DS4_DIR/gguf/dspark-0731.gguf}"
DS4_PORT="${DS4_PORT:-8010}"
DS4_HOST="${DS4_HOST:-127.0.0.1}"
DS4_CTX="${DS4_CTX:-131072}"
DS4_KV_DIR="${DS4_KV_DIR:-$HOME/.ds4/server-kv}"
DS4_KV_MB="${DS4_KV_MB:-16384}"
DS4_LOG="${DS4_LOG:-$DS4_DIR/logs/server.log}"
DS4_DSPARK="${DS4_DSPARK:-0}"
START_DS4="${START_DS4:-1}"
START_OD="${START_OD:-1}"
OPEN_BROWSER="${OPEN_BROWSER:-1}"
MODEL="${LATERALUS_MODEL:-deepseek-v4-flash}"
WB_PORT="${LATERALUS_WORKBENCH_PORT:-7860}"
OD_DAEMON_URL="${OD_DAEMON_URL:-http://127.0.0.1:7456}"
BASE_URL="http://${DS4_HOST}:${DS4_PORT}/v1"
CONFIG="${CONFIG:-resources/lateralus/ds4-workbench.edn}"

STARTED_DS4=0
STARTED_OD=0
DS4_PID=""
OD_PID=""

log() { printf '==> %s\n' "$*"; }
err() { printf 'error: %s\n' "$*" >&2; }

open_browser() {
  local url="$1"
  case "$(uname -s 2>/dev/null || echo unknown)" in
    Darwin) command -v open >/dev/null && open "$url" || true ;;
    *)      command -v xdg-open >/dev/null && xdg-open "$url" || true ;;
  esac
}

cleanup() {
  local code=$?
  if [[ "$STARTED_OD" -eq 1 && -n "${OD_PID}" ]] && kill -0 "$OD_PID" 2>/dev/null; then
    log "stopping Open Design daemon (pid $OD_PID)"
    kill "$OD_PID" 2>/dev/null || true
    wait "$OD_PID" 2>/dev/null || true
  fi
  if [[ "$STARTED_DS4" -eq 1 && -n "${DS4_PID}" ]] && kill -0 "$DS4_PID" 2>/dev/null; then
    log "stopping ds4-server (pid $DS4_PID)"
    kill "$DS4_PID" 2>/dev/null || true
    wait "$DS4_PID" 2>/dev/null || true
  fi
  exit "$code"
}
trap cleanup EXIT INT TERM

ds4_ok() {
  curl -sf --max-time 2 "${BASE_URL}/models" >/dev/null 2>&1
}

od_ok() {
  curl -sf --max-time 2 "${OD_DAEMON_URL}/health" >/dev/null 2>&1 \
    || curl -sf --max-time 2 "${OD_DAEMON_URL}/" >/dev/null 2>&1
}

wait_ds4() {
  local i
  log "waiting for ds4-server at ${BASE_URL} (model load can take several minutes)"
  for i in $(seq 1 600); do
    if ds4_ok; then
      log "ds4-server ready"
      return 0
    fi
    if [[ -n "${DS4_PID}" ]] && ! kill -0 "$DS4_PID" 2>/dev/null; then
      err "ds4-server exited before becoming ready — see ${DS4_LOG}"
      return 1
    fi
    sleep 1
  done
  err "ds4-server did not become ready within 10 minutes — see ${DS4_LOG}"
  return 1
}

start_ds4() {
  if [[ ! -x "$DS4_BIN" ]]; then
    err "ds4-server not found/executable at ${DS4_BIN}"
    exit 1
  fi
  if [[ ! -f "$DS4_MODEL" ]]; then
    err "model GGUF missing: ${DS4_MODEL}"
    exit 1
  fi
  mkdir -p "$(dirname "$DS4_LOG")" "$DS4_KV_DIR"

  local -a args=(
    --chdir "$DS4_DIR"
    -m "$DS4_MODEL"
    --metal
    --host "$DS4_HOST"
    --port "$DS4_PORT"
    --ctx "$DS4_CTX"
    --kv-disk-dir "$DS4_KV_DIR"
    --kv-disk-space-mb "$DS4_KV_MB"
  )

  if [[ "$DS4_DSPARK" == "1" ]]; then
    if [[ ! -f "$DS4_MTP" ]]; then
      err "DS4_DSPARK=1 but MTP file missing: ${DS4_MTP}"
      exit 1
    fi
    args+=(--mtp "$DS4_MTP" --dspark)
    log "DSpark MTP enabled (${DS4_MTP})"
  fi

  # shellcheck disable=SC2206
  if [[ -n "${DS4_EXTRA_ARGS:-}" ]]; then
    # Intentionally word-split so users can pass multiple flags.
    args+=(${DS4_EXTRA_ARGS})
  fi

  log "starting ds4-server → ${BASE_URL}"
  log "  model=${DS4_MODEL}"
  log "  ctx=${DS4_CTX} kv=${DS4_KV_DIR} (${DS4_KV_MB} MiB)"
  (
    cd "$DS4_DIR"
    exec "$DS4_BIN" "${args[@]}"
  ) >>"$DS4_LOG" 2>&1 &
  DS4_PID=$!
  STARTED_DS4=1
  wait_ds4
}

start_od() {
  local od_script="$ROOT/scripts/start-open-design-daemon.sh"
  if [[ ! -x "$od_script" ]]; then
    log "Open Design launcher missing (${od_script}); continuing without OD"
    return 0
  fi
  if od_ok; then
    log "Open Design daemon already up at ${OD_DAEMON_URL}"
    return 0
  fi
  log "starting Open Design daemon at ${OD_DAEMON_URL}"
  OD_DAEMON_URL="$OD_DAEMON_URL" "$od_script" >/tmp/lateralus-od-daemon.log 2>&1 &
  OD_PID=$!
  STARTED_OD=1
  local i
  for i in $(seq 1 30); do
    if od_ok; then
      log "Open Design daemon ready"
      return 0
    fi
    if ! kill -0 "$OD_PID" 2>/dev/null; then
      log "Open Design daemon failed to start (see /tmp/lateralus-od-daemon.log); continuing without it"
      STARTED_OD=0
      OD_PID=""
      return 0
    fi
    sleep 1
  done
  log "Open Design daemon not ready yet; MCP tools may fail until it is"
}

# --- main -------------------------------------------------------------------

if ! command -v clojure >/dev/null 2>&1; then
  err "clojure not on PATH"
  exit 1
fi

if [[ "$START_DS4" == "1" ]]; then
  if ds4_ok; then
    log "ds4-server already up at ${BASE_URL} (not restarting)"
  else
    start_ds4
  fi
else
  if ! ds4_ok; then
    err "START_DS4=0 but ${BASE_URL}/models is unreachable"
    exit 1
  fi
  log "using existing ds4-server at ${BASE_URL}"
fi

if [[ "$START_OD" == "1" ]]; then
  start_od
fi

log "models at ${BASE_URL}:"
curl -sf --max-time 5 "${BASE_URL}/models" | head -c 2000 || true
echo ""

if [[ "$OPEN_BROWSER" == "1" ]]; then
  ( sleep 4 && open_browser "http://127.0.0.1:${WB_PORT}" ) >/dev/null 2>&1 &
fi

log "starting Lateralus workbench (full tooling)"
log "  config=${CONFIG}"
log "  model=${MODEL}  url=${BASE_URL}"
log "  UI: http://127.0.0.1:${WB_PORT}"
echo ""

# Model load is expensive: leave ds4-server up by default after Lateralus exits.
# Set STOP_DS4=1 to tear it down. OD daemon (cheap) stops if we started it.
STOP_DS4="${STOP_DS4:-0}"
trap - EXIT INT TERM

_stop_children() {
  if [[ "$STARTED_OD" -eq 1 && -n "${OD_PID}" ]] && kill -0 "$OD_PID" 2>/dev/null; then
    log "stopping Open Design daemon (pid $OD_PID)"
    kill "$OD_PID" 2>/dev/null || true
  fi
  if [[ "$STOP_DS4" == "1" && "$STARTED_DS4" -eq 1 && -n "${DS4_PID}" ]] && kill -0 "$DS4_PID" 2>/dev/null; then
    log "stopping ds4-server (pid $DS4_PID)"
    kill "$DS4_PID" 2>/dev/null || true
  elif [[ "$STARTED_DS4" -eq 1 && -n "${DS4_PID}" ]]; then
    log "leaving ds4-server running (pid $DS4_PID) at ${BASE_URL} — STOP_DS4=1 to kill next time"
  fi
}
trap _stop_children EXIT INT TERM

clojure -M:workbench:run -i \
  --config "$CONFIG" \
  --base-url "$BASE_URL" \
  --model "$MODEL"
