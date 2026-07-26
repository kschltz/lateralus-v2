#!/usr/bin/env bash
# Wrapper to spawn Open Design's stdio MCP server for Lateralus.
#
# Env overrides:
#   OD_NODE         Node-compatible runtime (defaults to `node` on PATH)
#   OD_BIN          Path to Open Design's daemon CLI entry
#                   (defaults to the sibling checkout path on this machine)
#   OD_DAEMON_URL   Running daemon base URL (default: http://127.0.0.1:7456)
#
# Usage from Lateralus config:
#   {:command "scripts/open-design-mcp-server.sh" :args []}

set -euo pipefail

OD_NODE="${OD_NODE:-$(command -v node || true)}"
OD_BIN="${OD_BIN:-/Users/schltzk/projects/open-design/apps/daemon/bin/od.mjs}"
OD_DAEMON_URL="${OD_DAEMON_URL:-http://127.0.0.1:7456}"

if [[ -z "${OD_NODE}" || ! -x "${OD_NODE}" ]]; then
  echo "open-design-mcp-server: OD_NODE not set and no node on PATH" >&2
  exit 1
fi

if [[ ! -f "${OD_BIN}" ]]; then
  echo "open-design-mcp-server: OD_BIN not found at ${OD_BIN}" >&2
  exit 1
fi

exec "${OD_NODE}" "${OD_BIN}" mcp --daemon-url "${OD_DAEMON_URL}"
