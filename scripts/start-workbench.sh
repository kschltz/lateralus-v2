#!/usr/bin/env bash
# Start lateralus workbench (Docker): interactive profile gate, then CHAT | Portal.
# Default: talk to host Ollama (Desktop / `ollama serve`) over the network —
# no model-store mount, no copy. Compose Ollama is only a fallback.
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

export HOME="${HOME:-${USERPROFILE:-$ROOT}}"

# ---- Per-profile API keys ------------------------------------------------
# Profiles never store secrets; keys come from the environment:
#   LATERALUS_PROFILE_<NAME>_API_KEY  — key for profile <NAME> only
#   LATERALUS_API_KEY                 — any non-ollama profile
#   OLLAMA_API_KEY                    — ollama-based profiles only
# Any currently-set LATERALUS_PROFILE_*_API_KEY / LATERALUS_API_KEY vars
# are forwarded into the container so each profile picks up its own key.
forward_key_envs=()
while IFS= read -r var; do
  [[ -n "$var" ]] && forward_key_envs+=(-e "$var")
done < <(env | grep -oE '^LATERALUS_PROFILE_[A-Z0-9_]+_API_KEY' | sort -u)
if [[ -n "${LATERALUS_API_KEY:-}" ]]; then
  forward_key_envs+=(-e "LATERALUS_API_KEY")
fi

open_browser() {
  local url="$1"
  case "$(uname -s 2>/dev/null || echo unknown)" in
    Darwin)  command -v open >/dev/null && open "$url" || true ;;
    MINGW*|MSYS*|CYGWIN*) command -v cmd.exe >/dev/null && cmd.exe /c start "" "$url" || true ;;
    *)       command -v xdg-open >/dev/null && xdg-open "$url" || true ;;
  esac
}

host_ollama_ok() {
  curl -sf --max-time 2 "http://127.0.0.1:11434/api/tags" >/dev/null 2>&1
}

compose_ollama_running() {
  "${COMPOSE[@]}" ps --status running --services 2>/dev/null | grep -qx ollama
}

wait_host_ollama() {
  local i
  for i in $(seq 1 45); do
    if host_ollama_ok; then
      return 0
    fi
    sleep 1
  done
  return 1
}

src_fingerprint() {
  # Stamp of files baked into the uberjar (tracked + dirty + untracked).
  # Used as --build-arg so the jar layer rebuilds when the tree changes.
  (
    cd "$ROOT"
    find src resources -type f ! -name '.DS_Store' \
      | LC_ALL=C sort \
      | xargs shasum \
      | shasum \
      | awk '{print $1}'
  )
}

stop_old_workbench() {
  # `compose run` leaves a container on :7860; a later start then cache-hits
  # the image and never replaces that process, so CHAT keeps serving the old jar.
  echo "==> stopping any workbench already publishing :7860 or :7870"
  local ids
  ids="$(
    {
      docker ps -q --filter publish=7860
      docker ps -q --filter publish=7870
    } | awk 'NF && !seen[$0]++'
  )"
  if [[ -n "$ids" ]]; then
    # shellcheck disable=SC2086
    docker stop $ids
  else
    echo "    none running"
  fi
}

echo "==> checking Docker"
if ! docker info >/dev/null 2>&1; then
  echo "error: Docker is not running. Start Docker Desktop (or the daemon) and retry." >&2
  exit 1
fi

stop_old_workbench

export LATERALUS_SRC_REV="$(src_fingerprint)"
echo "==> building lateralus image from current tree (src-rev ${LATERALUS_SRC_REV:0:12})"
BUILD_ARGS=(build --build-arg "LATERALUS_SRC_REV=$LATERALUS_SRC_REV")
if [[ "${LATERALUS_DOCKER_NO_CACHE:-0}" == "1" ]]; then
  BUILD_ARGS+=(--no-cache)
fi
"${COMPOSE[@]}" "${BUILD_ARGS[@]}" lateralus
IMAGE_ID="$(docker image inspect lateralus-v2-lateralus:latest --format '{{.Id}} {{.Created}}' 2>/dev/null || true)"
echo "==> image ${IMAGE_ID:-lateralus-v2-lateralus:latest}"

USE_HOST_OLLAMA=0
# Build run argv explicitly so empty extras don't trip `set -u`.
# --build makes `run` use the image we just built, not a leftover tag.
RUN_ARGS=(run --rm --service-ports --build)

if [[ "${LATERALUS_FORCE_DOCKER_OLLAMA:-0}" != "1" ]]; then
  # Compose publishes :11434 and blocks Desktop — stop it so we can reference the host.
  if compose_ollama_running; then
    echo "==> stopping compose Ollama (will use host daemon instead of mounting models)"
    "${COMPOSE[@]}" stop ollama >/dev/null
  fi

  if ! host_ollama_ok; then
    case "$(uname -s 2>/dev/null || echo unknown)" in
      Darwin)
        if [[ -d /Applications/Ollama.app ]]; then
          echo "==> starting Ollama.app on the host"
          open -a Ollama || true
        fi
        ;;
    esac
  fi

  if wait_host_ollama; then
    USE_HOST_OLLAMA=1
    export LATERALUS_DOCKER_OLLAMA_URL="${LATERALUS_DOCKER_OLLAMA_URL:-http://host.docker.internal:11434/v1}"
    RUN_ARGS+=(--no-deps)
    echo "==> using host Ollama via $LATERALUS_DOCKER_OLLAMA_URL"
    echo "    No model copy/mount — lateralus calls your host pulls over the network."
    if command -v ollama >/dev/null 2>&1; then
      echo "==> host models:"
      ollama list || true
      if ! ollama list 2>/dev/null | awk 'NR>1 {print $1}' | grep -qx "$MODEL" \
         && ! ollama list 2>/dev/null | awk 'NR>1 {print $1}' | grep -qx "${MODEL}:latest"; then
        echo "==> ensuring model '${MODEL}' is available on the host"
        ollama pull "$MODEL" || true
      fi
    fi
  else
    echo "==> host Ollama not reachable on :11434 — falling back to compose Ollama" >&2
    echo "    (Install/start Ollama Desktop, or set LATERALUS_FORCE_DOCKER_OLLAMA=1.)" >&2
  fi
fi

if [[ "$USE_HOST_OLLAMA" -eq 0 ]]; then
  export LATERALUS_DOCKER_OLLAMA_URL="${LATERALUS_DOCKER_OLLAMA_URL:-http://ollama:11434/v1}"
  echo "==> starting compose Ollama (isolated volume; not your Desktop store)"
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
fi

echo ""
echo "==> starting lateralus (interactive profile gate, then workbench)"
echo "    Workbench UI: http://localhost:7860"
echo "    Ollama URL inside container: $LATERALUS_DOCKER_OLLAMA_URL"
if [[ "$USE_HOST_OLLAMA" -eq 1 ]]; then
  echo "    Tip: localhost:11434 profiles rewrite to host.docker.internal."
else
  echo "    Tip: pick the 'docker' profile (http://ollama:11434/v1)."
fi
echo "    Press ? on Model for pulled local models (cloud catalog off in Docker)."
echo ""

( sleep 4 && open_browser "http://localhost:7860" ) >/dev/null 2>&1 &

LATERALUS_MODEL="$MODEL" \
  LATERALUS_DOCKER_OLLAMA_URL="$LATERALUS_DOCKER_OLLAMA_URL" \
  "${COMPOSE[@]}" "${RUN_ARGS[@]}" ${forward_key_envs[@]+"${forward_key_envs[@]}"} lateralus -i
