/**
 * D102: runs `scripts/map-template.mjs` under esbuild, so the build can import the console's own
 * PositionMap and i18n (TypeScript + JSX) without adding a loader to the pipeline.
 *
 * Usage: node scripts/build-map-template.mjs [--check]
 */
import { execFileSync } from "node:child_process";
import { mkdirSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";

const root = resolve(process.cwd());
const scratch = resolve(root, ".build");
mkdirSync(scratch, { recursive: true });

const entry = resolve(scratch, "map-template.mjs");
const bundle = resolve(scratch, "map-template.cjs");

// `node_modules/.bin/esbuild` is the native binary itself on this install, not a JS launcher: run it
// directly rather than through `node`.
execFileSync(
  resolve(root, "node_modules", ".bin", "esbuild"),
  [
    resolve(root, "scripts", "map-template.mjs"),
    "--bundle",
    "--platform=node",
    "--format=cjs",
    "--loader:.tsx=tsx",
    "--loader:.ts=ts",
    "--jsx=automatic",
    '--define:import.meta.env.VITE_API_BASE_URL=""',
    `--outfile=${bundle}`,
    "--log-level=error",
  ],
  { stdio: "inherit", cwd: root },
);

const args = process.argv.includes("--check") ? ["--check"] : [];
execFileSync("node", [bundle, ...args], { stdio: "inherit", cwd: root });
rmSync(entry, { force: true });
