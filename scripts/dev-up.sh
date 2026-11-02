#!/usr/bin/env bash
# Boots the backend on a machine with no Docker and no PostgreSQL install.
#
#   scripts/dev-up.sh     start the embedded database and the application
#   scripts/dev-down.sh   stop both
#
# The database is a real PostgreSQL started from the Maven test dependency, on 5433. Its superuser is
# "postgres" with trust authentication, so no credential is needed. The application creates the
# "bizsim" database on first start (DatabaseBootstrap) and Flyway builds the schema.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/backend"

PORT_DB="${BIZSIM_LOCAL_DB_PORT:-5433}"
PORT_APP="${BIZSIM_PORT:-8080}"
RUN_DIR="$ROOT/.dev"
mkdir -p "$RUN_DIR"

DB_USER="postgres"
DB_NAME="bizsim"
DB_URL="jdbc:postgresql://localhost:${PORT_DB}/${DB_NAME}"
MAINTENANCE_URL="jdbc:postgresql://localhost:${PORT_DB}/postgres"

echo "==> building (tests skipped)"
mvn -q -DskipTests package

echo "==> resolving runtime and test classpath"
mvn -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$RUN_DIR/classpath.txt"
CP="target/classes:target/test-classes:$(cat "$RUN_DIR/classpath.txt")"

echo "==> starting embedded PostgreSQL on ${PORT_DB}"
java -cp "$CP" com.bizsim.dev.EmbeddedDatabaseMain "$PORT_DB" > "$RUN_DIR/db.log" 2>&1 &
echo $! > "$RUN_DIR/db.pid"

for _ in $(seq 1 60); do
  grep -q EMBEDDED_PG_READY "$RUN_DIR/db.log" 2>/dev/null && break
  sleep 1
done
grep -q EMBEDDED_PG_READY "$RUN_DIR/db.log" || { echo "database did not start:"; tail -20 "$RUN_DIR/db.log"; exit 1; }

echo "==> starting the application on ${PORT_APP} (profile dev)"
BIZSIM_PORT="$PORT_APP" BIZSIM_DB_URL="$DB_URL" BIZSIM_DB_USER="$DB_USER" \
  java -jar target/bizsim-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev \
  --bizsim.web.dir="$ROOT/web/dist" \
  > "$RUN_DIR/app.log" 2>&1 &
echo $! > "$RUN_DIR/app.pid"

for _ in $(seq 1 90); do
  grep -q "Started BizsimApplication" "$RUN_DIR/app.log" 2>/dev/null && break
  sleep 1
done
if ! grep -q "Started BizsimApplication" "$RUN_DIR/app.log"; then
  echo "application did not start; last lines of .dev/app.log:"
  tail -30 "$RUN_DIR/app.log"
  exit 1
fi

echo
echo "backend:  http://localhost:${PORT_APP}"
echo "database: ${MAINTENANCE_URL}  (database '${DB_NAME}' created on first start)"
echo "tenant demo, password demo1234, users: trainer, player, admin (see README)"
echo "logs:     .dev/app.log  .dev/db.log"
echo "stop:     scripts/dev-down.sh"
