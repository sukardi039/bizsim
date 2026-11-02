#!/usr/bin/env bash
# Starts the facilitator console in development mode, reachable from other machines.
#
#   scripts/dev-up.sh     # backend + database first
#   scripts/dev-web.sh    # console on 0.0.0.0:5173
#
# Binds to all interfaces because the usual case is a remote box reached over SSH; the Vite proxy
# forwards /api to the backend on 8080, so the browser only ever talks to one origin.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/web"

if [ ! -d node_modules ]; then
  echo "==> installing web dependencies (first run only)"
  npm install
fi

PORT="${BIZSIM_WEB_PORT:-5173}"
echo "==> facilitator console on http://0.0.0.0:${PORT}"
echo "    sign in with the seeded demo accounts (DevSeeder), or see README"
exec npm run dev -- --host 0.0.0.0 --port "$PORT"
