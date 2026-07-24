#!/usr/bin/env bash
# Start lateralus workbench (Docker): interactive profile setup, then CHAT | Portal.
# Works on macOS, Linux, and Windows (WSL / Git Bash) with Docker Desktop.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MODEL="${LATERALUS_MODEL:-llama3.2}"
COMPOSE=(docker compose)
if ! docker compose version >/dev/null 2>&1; then
  if command -v docker-compose >/dev/null 2>&1; then
    COMPOSE=(docker-compose)
  else
    echo "error: need Docker Compose (docker compose or docker-compose)" >&2
    exit 1
  fi
fi

open_browser() {
  local url="$1"
  case "$(uname -s 2>/dev/null || echo unknown)" in
    Darwin)  command -v open >/dev/null && open "$url" || true ;;
    MINGW*|MSYS*|CYGWIN*) command -v cmd.exe >/dev/null && cmd.exe /c start "" "$url" || true ;;
    *)       command -v xdg-open >/dev/null && xdg-open "$url" || true ;;
  esac
}

echo "==> checking Docker"
if ! docker info >/dev/null 2>&1; then
  echo "error: Docker is not running. Start Docker Desktop (or the daemon) and retry." >&2
  exit 1
fi

echo "==> starting Ollama"
"${COMPOSE[@]}" up -d ollama

echo "==> waiting for Ollama"
for i in $(seq 1 60); do
  if "${COMPOSE[@]}" exec -T ollama ollama list >/dev/null 2>&1; then
    break
  fi
  if [[ "$i" -eq 60 ]]; then
    echo "error: Ollama did not become ready in time" >&2
    exit 1
  fi
  sleep 1
done

echo "==> ensuring model '${MODEL}' is available"
LATERALUS_MODEL="$MODEL" "${COMPOSE[@]}" --profile setup run --rm pull-model

echo ""
echo "==> starting lateralus (interactive profile gate, then workbench)"
echo "    Workbench UI: http://localhost:7860"
echo "    Tip: pick the 'docker' profile (base-url http://ollama:11434/v1)."
echo "    Host profiles that use localhost:11434 will be rewritten to ollama inside Docker."
echo ""

# Open the UI shortly after the container is up (workbench binds 0.0.0.0:7860).
( sleep 4 && open_browser "http://localhost:7860" ) >/dev/null 2>&1 &

LATERALUS_MODEL="$MODEL" \
  "${COMPOSE[@]}" run --rm --service-ports lateralus -i
