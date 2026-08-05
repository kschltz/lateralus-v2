#!/usr/bin/env bash
# Ollama Cloud + dynamic MCP lifecycle demo (stdin feeder): list→ADD→use→EDIT→REMOVE.
# Prefer the PTY driver for screen recordings:
#   python3 scripts/demo-ollama-cloud-mcp-dynamic-pty.py
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

CONFIG="${CONFIG:-resources/lateralus/demo-ollama-cloud-mcp-dynamic.edn}"
START_MODEL="${START_MODEL:-deepseek-v4-flash}"
BASE_URL="${BASE_URL:-https://ollama.com/v1}"
SERVER_ID="${MCP_SERVER_ID:-demo}"

if [[ -z "${OLLAMA_API_KEY:-}" ]]; then
  echo "OLLAMA_API_KEY is not set" >&2
  exit 1
fi

FAKE_LOG="$(mktemp)"
FAKE_PID=""
cleanup() {
  [[ -n "$FAKE_PID" ]] && kill "$FAKE_PID" 2>/dev/null || true
  rm -f "$FAKE_LOG"
}
trap cleanup EXIT

clojure -M:dev -m fake-mcp-http-server >"$FAKE_LOG" 2>&1 &
FAKE_PID=$!

URL=""
for _ in $(seq 1 90); do
  if grep -q '^http' "$FAKE_LOG" 2>/dev/null; then
    URL="$(grep -m1 '^http' "$FAKE_LOG")"
    break
  fi
  if ! kill -0 "$FAKE_PID" 2>/dev/null; then
    echo "fake MCP server exited early:" >&2
    cat "$FAKE_LOG" >&2
    exit 1
  fi
  sleep 1
done

if [[ -z "$URL" ]]; then
  echo "timed out waiting for fake MCP URL" >&2
  cat "$FAKE_LOG" >&2
  exit 1
fi

UPSERT_JSON="{\"server-id\":\"${SERVER_ID}\",\"config\":{\"transport\":\"http\",\"url\":\"${URL}\",\"allow-http?\":true,\"allow-loopback?\":true,\"request-timeout-ms\":15000}}"
EDIT_JSON="{\"server-id\":\"${SERVER_ID}\",\"config\":{\"transport\":\"http\",\"url\":\"${URL}\",\"allow-http?\":true,\"allow-loopback?\":true,\"request-timeout-ms\":30000,\"max-result-bytes\":32768}}"

echo
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  lateralus — Ollama Cloud + dynamic MCP lifecycle demo       ║"
echo "║  flow: list → ADD → use → EDIT → REMOVE → list               ║"
echo "║  model: ${START_MODEL}"
echo "║  endpoint: ${BASE_URL}"
echo "║  fake MCP: ${URL}"
echo "╚══════════════════════════════════════════════════════════════╝"
echo

PROMPTS_FILE="$(mktemp)"
trap 'rm -f "$PROMPTS_FILE"; cleanup' EXIT

cat >"$PROMPTS_FILE" <<EOF
Call mcp_list_servers. In your final answer, report dynamic-enabled? and the server count only.
ADD: Use mcp_upsert_server exactly once with these arguments (JSON): ${UPSERT_JSON} Then call mcp_list_servers. Final answer: the server-id and the tool names that were discovered.
Call the MCP tool ${SERVER_ID}_echo with message "ollama-cloud-mcp-demo". Final answer: quote the echoed content only.
EDIT/REPLACE: Use mcp_upsert_server exactly once with these arguments (JSON): ${EDIT_JSON} This replaces the same server-id with updated timeouts. Then call mcp_list_servers. Final answer: confirm the server is still connected and name one tool.
REMOVE: Use mcp_remove_server with server-id "${SERVER_ID}". Then call mcp_list_servers. Final answer: report the server count only (should be 0).
EOF

type_line() {
  local s="$1"
  local ch
  sleep 0.6
  for ((i = 0; i < ${#s}; i++)); do
    ch="${s:i:1}"
    printf '%s' "$ch"
    sleep 0.014
  done
  printf '\n'
}

{
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" ]] && continue
    type_line "$line"
  done <"$PROMPTS_FILE"
} | clojure -M:dev:run -i \
    --config "$CONFIG" \
    --base-url "$BASE_URL" \
    --model "$START_MODEL"

echo
echo "── demo complete ──"
