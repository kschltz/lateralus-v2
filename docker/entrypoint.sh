#!/usr/bin/env bash
# lateralus container entrypoint — JVM flags baked in; optional first-run profile.
set -euo pipefail

JAR="${LATERALUS_JAR:-/app/lateralus.jar}"
CONFIG_HOME="${LATERALUS_CONFIG_HOME:-/data/config}"
# Defaults used ONLY when seeding a brand-new config volume.
SEED_BASE_URL="${LATERALUS_BASE_URL:-http://ollama:11434/v1}"
SEED_MODEL="${LATERALUS_MODEL:-llama3.2}"
PROFILE_NAME="${LATERALUS_PROFILE:-docker}"

mkdir -p "$CONFIG_HOME/profiles"

# Seed a Docker-oriented *local* profile once. Do not re-export
# LATERALUS_BASE_URL on later starts — that used to override an
# ollama-cloud profile chosen in the interactive gate.
if [[ ! -f "$CONFIG_HOME/config.edn" ]]; then
  MODEL_EDN=$(printf '%s' "$SEED_MODEL" | sed 's/"/\\"/g')
  cat > "$CONFIG_HOME/profiles/${PROFILE_NAME}.edn" <<EDN
{:backend :ollama-local
 :base-url "${SEED_BASE_URL}"
 :model "${MODEL_EDN}"
 :web-provider :ddg
 :workbench? true}
EDN
  printf '%s\n' "{:active-profile \"${PROFILE_NAME}\"}" > "$CONFIG_HOME/config.edn"
  echo "lateralus: seeded profile '${PROFILE_NAME}' → ${SEED_BASE_URL} model=${SEED_MODEL}" >&2
  echo "lateralus: for Ollama Cloud, pick starter 3 / edit profile (needs OLLAMA_API_KEY)." >&2
fi

export LATERALUS_CONFIG_HOME="$CONFIG_HOME"
export LATERALUS_IN_DOCKER="${LATERALUS_IN_DOCKER:-1}"
export LATERALUS_DOCKER_OLLAMA_URL="${LATERALUS_DOCKER_OLLAMA_URL:-http://ollama:11434/v1}"
export LATERALUS_WORKBENCH_HOST="${LATERALUS_WORKBENCH_HOST:-0.0.0.0}"
export LATERALUS_WORKBENCH_PUBLIC_HOST="${LATERALUS_WORKBENCH_PUBLIC_HOST:-localhost}"
export LATERALUS_PORTAL_PORT="${LATERALUS_PORTAL_PORT:-7870}"

exec java \
  --add-modules=jdk.incubator.vector \
  --enable-native-access=ALL-UNNAMED \
  -jar "$JAR" \
  "$@"
