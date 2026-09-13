#!/usr/bin/env bash
# Start lateralus workbench: interactive profile gate, then CHAT | Portal.
#
# Runtime (local Clojure vs Docker):
#   ./scripts/start-workbench                  # TTY: prompt; non-TTY: docker
#   ./scripts/start-workbench --local          # Java 22+ + clojure CLI on this machine
#   ./scripts/start-workbench --docker          # uberjar in Compose
#   LATERALUS_WORKBENCH_RUNTIME=local|docker
#
# Store choice:
#   ./scripts/start-workbench --store duckdb   # sessions/stream/file-index in one DuckDB file
#   ./scripts/start-workbench --store memory   # built-in defaults (default when omitted)
#   LATERALUS_STORE=duckdb ./scripts/start-workbench
#   LATERALUS_STORE_PATH=/data/config/my.duckdb ./scripts/start-workbench --store duckdb
#
# Default Docker path talks to host Ollama (Desktop / `ollama serve`) over the
# network — no model-store mount, no copy. Compose Ollama is only a fallback.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MODEL="${LATERALUS_MODEL:-llama3.2}"
DRY_RUN=0
RUNTIME="${LATERALUS_WORKBENCH_RUNTIME:-}"

usage() {
  cat <<'EOF'
Usage: ./scripts/start-workbench [options]

Run the CHAT | Portal workbench via local Clojure or Docker.

Runtime:
  --local                 Java 22+ clojure CLI (repo as workspace)
  --docker                Compose uberjar (isolated config volume)
  --mode local|docker     Same as --local / --docker
  LATERALUS_WORKBENCH_RUNTIME=local|docker
                          Non-interactive runtime (skips the prompt)

  On a TTY with no runtime flag, the script asks 1=local / 2=docker.
  Without a TTY, the default is docker (previous behaviour).

Store:
  --store memory|duckdb  LATERALUS_STORE (flag wins over env)
  --store=memory|duckdb

Other:
  --dry-run               Print the chosen command and exit
  -h, --help              Show this help

Local Clojure uses the repo checkout as :workspace-root (file tools,
clojure_* edits, tool_promote, reload_runtime). Docker now also bind-mounts
that checkout at /workspace so file tools see the same tree; remaining
Docker gaps are the isolated /data/config profile volume and the skipped
Ollama Cloud catalog merge (LATERALUS_LIST_CLOUD=1 to opt in).
EOF
}

# --store / --local / --docker (flag wins over pre-set env).
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --local)
      RUNTIME=local
      shift
      ;;
    --docker)
      RUNTIME=docker
      shift
      ;;
    --mode)
      [[ $# -ge 2 ]] || { echo "error: --mode requires a value (local | docker)" >&2; exit 1; }
      case "$2" in
        local|docker) RUNTIME="$2"; shift 2 ;;
        *) echo "error: unknown --mode '$2' (expected local | docker)" >&2; exit 1 ;;
      esac
      ;;
    --mode=*)
      case "${1#--mode=}" in
        local|docker) RUNTIME="${1#--mode=}"; shift ;;
        *) echo "error: unknown --mode '${1#--mode=}' (expected local | docker)" >&2; exit 1 ;;
      esac
      ;;
    --store)
      [[ $# -ge 2 ]] || { echo "error: --store requires a value (memory | duckdb)" >&2; exit 1; }
      case "$2" in
        memory|duckdb) LATERALUS_STORE="$2"; shift 2 ;;
        *) echo "error: unknown --store '$2' (expected memory | duckdb)" >&2; exit 1 ;;
      esac
      ;;
    --store=*)
      case "${1#--store=}" in
        memory|duckdb) LATERALUS_STORE="${1#--store=}"; shift ;;
        *) echo "error: unknown --store '${1#--store=}' (expected memory | duckdb)" >&2; exit 1 ;;
      esac
      ;;
    *) echo "error: unknown option '$1' (see --help)" >&2; exit 1 ;;
  esac
done
export LATERALUS_STORE="${LATERALUS_STORE:-}"
export HOME="${HOME:-${USERPROFILE:-$ROOT}}"

prompt_runtime() {
  local default_choice=2
  local default_label="docker"
  if command -v clojure >/dev/null 2>&1; then
    default_choice=1
    default_label="local"
  fi
  cat <<EOF >&2

How do you want to run the Lateralus workbench?

  1) Local Clojure   — Java 22+ on this machine
                       repo checkout is the workspace (file_create, clojure_*
                       edits, tool_promote, reload_runtime). Profiles in
                       ~/.config/lateralus. Model ? includes Ollama Cloud when
                       OLLAMA_API_KEY is set.

  2) Docker          — uberjar in Compose (isolated /data/config volume)
                       host checkout is mounted at /workspace so file tools
                       see the same tree. Cloud catalog merge is off unless
                       LATERALUS_LIST_CLOUD=1.

EOF
  printf 'Choice [%s]: ' "$default_choice" >&2
  local answer=""
  if [[ -r /dev/tty ]]; then
    read -r answer </dev/tty || true
  else
    read -r answer || true
  fi
  answer="${answer:-$default_choice}"
  case "$answer" in
    1|local|clojure|l|L) echo local ;;
    2|docker|d|D) echo docker ;;
    "") echo "$default_label" ;;
    *)
      echo "error: unknown choice '$answer' (expected 1 or 2)" >&2
      exit 1
      ;;
  esac
}

choose_runtime() {
  case "${RUNTIME}" in
    local|docker) echo "$RUNTIME" ;;
    "")
      # Prompt only when stdin is a TTY so CI / piped `--dry-run` never hangs.
      # Pipe a choice with --local/--docker, or `script`/`pty` for the menu.
      if [[ -t 0 ]]; then
        prompt_runtime
      else
        echo "note: no TTY — using Docker (pass --local or --docker to choose)" >&2
        echo docker
      fi
      ;;
    *)
      echo "error: unknown LATERALUS_WORKBENCH_RUNTIME '$RUNTIME' (expected local | docker)" >&2
      exit 1
      ;;
  esac
}

open_browser() {
  local url="$1"
  case "$(uname -s 2>/dev/null || echo unknown)" in
    Darwin)  command -v open >/dev/null && open "$url" || true ;;
    MINGW*|MSYS*|CYGWIN*) command -v cmd.exe >/dev/null && cmd.exe /c start "" "$url" || true ;;
    *)       command -v xdg-open >/dev/null && xdg-open "$url" || true ;;
  esac
}

print_store_line() {
  if [[ -n "${LATERALUS_STORE:-}" ]]; then
    echo "    Store: $LATERALUS_STORE${LATERALUS_STORE_PATH:+ ($LATERALUS_STORE_PATH)}"
  else
    echo "    Store: memory (defaults) — use --store duckdb for a durable DuckDB store"
  fi
}

stop_old_workbench() {
  # `compose run` leaves a container on :7860; a later start then cache-hits
  # the image and never replaces that process, so CHAT keeps serving the old jar.
  if ! command -v docker >/dev/null 2>&1; then
    echo "==> skipping Docker workbench stop (docker not on PATH)"
    return 0
  fi
  if ! docker info >/dev/null 2>&1; then
    echo "==> skipping Docker workbench stop (daemon not running)"
    return 0
  fi
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

ensure_compose() {
  COMPOSE=(docker compose)
  if ! docker compose version >/dev/null 2>&1; then
    if command -v docker-compose >/dev/null 2>&1; then
      COMPOSE=(docker-compose)
    else
      echo "error: need Docker Compose (docker compose or docker-compose)" >&2
      exit 1
    fi
  fi
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

start_local() {
  if ! command -v clojure >/dev/null 2>&1; then
    echo "error: clojure CLI not found. Install Clojure or use --docker." >&2
    exit 1
  fi
  if ! command -v java >/dev/null 2>&1; then
    echo "error: java not found. Lateralus needs Java 22+ (jdk.incubator.vector)." >&2
    exit 1
  fi
  echo "==> runtime: local Clojure"
  echo "    Command: clojure -M:workbench:run -i"
  echo "    Workspace: $ROOT"
  echo "    Workbench UI: http://localhost:7860"
  print_store_line
  echo "    Profiles: \${LATERALUS_CONFIG_HOME:-\$HOME/.config/lateralus}"
  echo ""
  if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "mode=local"
    echo "workspace=$ROOT"
    echo "command=clojure -M:workbench:run -i"
    return 0
  fi
  stop_old_workbench
  ( sleep 4 && open_browser "http://localhost:7860" ) >/dev/null 2>&1 &
  exec clojure -M:workbench:run -i
}

start_docker() {
  # Host checkout bind-mounted at /workspace (see docker-compose.yml) so
  # file tools share the same tree as local Clojure.
  export LATERALUS_WORKSPACE="${LATERALUS_WORKSPACE:-$ROOT}"

  echo "==> runtime: Docker"
  echo "    Workspace mount: $LATERALUS_WORKSPACE -> /workspace"
  if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "mode=docker"
    echo "workspace=$LATERALUS_WORKSPACE"
    echo "command=docker compose run --rm --service-ports lateralus -i"
    return 0
  fi

  ensure_compose

  # ---- Per-profile API keys ------------------------------------------------
  # Profiles never store secrets; keys come from the environment:
  #   LATERALUS_PROFILE_<NAME>_API_KEY  — key for profile <NAME> only
  #   LATERALUS_API_KEY                 — any non-ollama profile
  #   OLLAMA_API_KEY                    — ollama-based profiles only
  local forward_key_envs=()
  local var
  while IFS= read -r var; do
    [[ -n "$var" ]] && forward_key_envs+=(-e "$var")
  done < <(env | grep -oE '^LATERALUS_PROFILE_[A-Z0-9_]+_API_KEY' | sort -u)
  if [[ -n "${LATERALUS_API_KEY:-}" ]]; then
    forward_key_envs+=(-e "LATERALUS_API_KEY")
  fi

  echo "==> checking Docker"
  if ! docker info >/dev/null 2>&1; then
    echo "error: Docker is not running. Start Docker Desktop (or the daemon) and retry." >&2
    exit 1
  fi

  stop_old_workbench

  export LATERALUS_SRC_REV="$(src_fingerprint)"
  echo "==> building lateralus image from current tree (src-rev ${LATERALUS_SRC_REV:0:12})"
  local BUILD_ARGS=(build --build-arg "LATERALUS_SRC_REV=$LATERALUS_SRC_REV")
  if [[ "${LATERALUS_DOCKER_NO_CACHE:-0}" == "1" ]]; then
    BUILD_ARGS+=(--no-cache)
  fi
  "${COMPOSE[@]}" "${BUILD_ARGS[@]}" lateralus
  local IMAGE_ID
  IMAGE_ID="$(docker image inspect lateralus-v2-lateralus:latest --format '{{.Id}} {{.Created}}' 2>/dev/null || true)"
  echo "==> image ${IMAGE_ID:-lateralus-v2-lateralus:latest}"

  local USE_HOST_OLLAMA=0
  # Build run argv explicitly so empty extras don't trip `set -u`.
  # --build makes `run` use the image we just built, not a leftover tag.
  local RUN_ARGS=(run --rm --service-ports --build)

  # Forward the store choice into the container (compose substitution covers
  # exported vars; explicit -e also covers a non-exported LATERALUS_STORE_PATH).
  local STORE_ENV_ARGS=()
  if [[ -n "${LATERALUS_STORE:-}" ]]; then
    STORE_ENV_ARGS+=(-e "LATERALUS_STORE")
  fi
  if [[ -n "${LATERALUS_STORE_PATH:-}" ]]; then
    STORE_ENV_ARGS+=(-e "LATERALUS_STORE_PATH")
  fi

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
    local i
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
  echo "    Workspace: /workspace (host $LATERALUS_WORKSPACE)"
  print_store_line
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
    LATERALUS_WORKSPACE="$LATERALUS_WORKSPACE" \
    "${COMPOSE[@]}" "${RUN_ARGS[@]}" ${STORE_ENV_ARGS[@]+"${STORE_ENV_ARGS[@]}"} \
    ${forward_key_envs[@]+"${forward_key_envs[@]}"} lateralus -i
}

CHOSEN="$(choose_runtime)"
case "$CHOSEN" in
  local) start_local ;;
  docker) start_docker ;;
  *) echo "error: internal: unknown runtime '$CHOSEN'" >&2; exit 1 ;;
esac
