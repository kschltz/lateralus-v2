#!/usr/bin/env bash
set -euo pipefail

OD_DAEMON_URL="${OD_DAEMON_URL:-http://127.0.0.1:7456}"
OD_DIR="${OD_DIR:-/Users/schltzk/projects/open-design/apps/daemon}"
OD_NODE="${OD_NODE:-$(command -v node)}"

if [[ ! -x "${OD_NODE}" ]]; then
  echo "start-open-design-daemon: no node executable on PATH" >&2
  exit 1
fi

cd "${OD_DIR}"

PORT=$(echo "${OD_DAEMON_URL}" | sed -E 's#.*:([0-9]+).*#\1#')
export OD_DAEMON_URL

exec "${OD_NODE}" dist/cli.js --port "${PORT}" --no-open
