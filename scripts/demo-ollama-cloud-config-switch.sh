#!/usr/bin/env bash
# Visual / interactive demo: Ollama Cloud session that switches models
# via set_llm_config. Requires OLLAMA_API_KEY.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

CONFIG="${CONFIG:-resources/lateralus/demo-ollama-cloud-config.edn}"
START_MODEL="${START_MODEL:-deepseek-v4-flash}"
NEXT_MODEL="${NEXT_MODEL:-gpt-oss:20b}"
BASE_URL="${BASE_URL:-https://ollama.com/v1}"

if [[ -z "${OLLAMA_API_KEY:-}" ]]; then
  echo "OLLAMA_API_KEY is not set" >&2
  exit 1
fi

type_line() {
  # Slow-type a line into the TTY for a readable recording, then Enter.
  local s="$1"
  local ch
  sleep 0.6
  for ((i = 0; i < ${#s}; i++)); do
    ch="${s:i:1}"
    printf '%s' "$ch"
    sleep 0.018
  done
  printf '\n'
}

export LATERALUS_DEMO_START_MODEL="$START_MODEL"
export LATERALUS_DEMO_NEXT_MODEL="$NEXT_MODEL"

PROMPTS_FILE="$(mktemp)"
trap 'rm -f "$PROMPTS_FILE"' EXIT

cat >"$PROMPTS_FILE" <<EOF
Call the self_status tool. In your final answer, quote only the configuration.model and configuration.base-url values — nothing else.
Use set_llm_config to change the session model to ${NEXT_MODEL}. Then call self_status again. In your final answer, state the old model, the new model, and confirm base-url is still ${BASE_URL}.
Reply with one short sentence that includes the exact model id you are currently configured to use (call self_status if unsure).
EOF

echo
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  lateralus — Ollama Cloud model switch demo                  ║"
echo "║  start: ${START_MODEL}"
echo "║  switch → ${NEXT_MODEL}"
echo "║  endpoint: ${BASE_URL}"
echo "╚══════════════════════════════════════════════════════════════╝"
echo

# Feed prompts one-by-one after each exchange completes: run under -i with
# a slow typer so a screen recording can follow the session.
{
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" ]] && continue
    type_line "$line"
    # Interactive mode blocks until the exchange finishes before reading
    # the next stdin line, so no extra wait is required between prompts.
  done <"$PROMPTS_FILE"
} | clojure -M:run -i \
    --config "$CONFIG" \
    --base-url "$BASE_URL" \
    --model "$START_MODEL"

echo
echo "── demo complete ──"
