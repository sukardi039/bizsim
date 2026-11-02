#!/usr/bin/env bash
# Stops whatever scripts/dev-up.sh started. Safe to run when nothing is running.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/.dev"

for name in app db; do
  pid_file="$RUN_DIR/${name}.pid"
  if [ -f "$pid_file" ]; then
    pid="$(cat "$pid_file")"
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      echo "stopped $name (pid $pid)"
    else
      echo "$name was not running"
    fi
    rm -f "$pid_file"
  else
    echo "no pid file for $name"
  fi
done
