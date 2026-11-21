/**
 * D119: where the web build finds the one authority for the limits.
 *
 * The authority is `backend/src/main/resources/limits.json` — the same file the server validates at startup
 * and answers at `GET /api/limits`. The console's build reads it too (map slots, palette check), but a
 * Docker build stage does not necessarily keep the repository's layout: the console image builds from
 * `web/`, and the api image's Node stage copies only `web/` into its workdir. So the file is looked up in a
 * few known places instead of assuming one — and if none has it, the build fails naming every path it tried
 * rather than with a bare ENOENT.
 *
 * Both Dockerfiles copy the file into one of these paths; nothing is duplicated in the repository.
 */
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

const CANDIDATES = [
  // The repository: scripts run with cwd = web/
  resolve(process.cwd(), "..", "backend", "src", "main", "resources", "limits.json"),
  // A stage whose workdir IS the repository root
  resolve(process.cwd(), "backend", "src", "main", "resources", "limits.json"),
  // A stage that copied the file beside the console (web/Dockerfile)
  resolve(process.cwd(), "limits.json"),
  // The api image's Node stage (WORKDIR /src/web, repository layout recreated above it)
  "/src/backend/src/main/resources/limits.json",
];

export function readLimits() {
  for (const path of CANDIDATES) {
    if (existsSync(path)) {
      return JSON.parse(readFileSync(path, "utf8"));
    }
  }
  throw new Error(
    "limits.json not found — it is the single authority for the console's limits. Looked in:\n  " +
      CANDIDATES.join("\n  "),
  );
}
