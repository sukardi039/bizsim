#!/usr/bin/env node
/**
 * D119: the copies must agree with limits.json.
 *
 * A rule used to live wherever it was enforced, and the copies drifted silently: ten in the API, ten in the
 * builder's picker, ten in the palette and **six** in the map template — so a 7–10 company class could play
 * but could not share its map. The driver of that defect was not carelessness; it was that nothing failed
 * when a fifth copy appeared.
 *
 * This gate is that failure. It runs inside `npm run build`, so a tree whose build-time artefacts disagree
 * with the one authority cannot produce a bundle — the same idea as the i18n gate (D116).
 *
 * Checks:
 *   1. the map template has exactly classSize.max slots (a smaller template silently truncates a class);
 *   2. the pie palette has at least classSize.max colours (a smaller palette reuses hues in silence);
 *   3. the console actually asks the server for the limits (a literal would otherwise creep back in).
 */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(process.cwd(), "..");
const limits = JSON.parse(readFileSync(resolve(root, "backend/src/main/resources/limits.json"), "utf8"));
const max = limits.classSize.max;

const problems = [];

const template = JSON.parse(readFileSync(resolve(process.cwd(), "public/map-template.json"), "utf8"));
if (template.slots.length !== max) {
  problems.push(
    `map-template.json has ${template.slots.length} slots but limits.json allows ${max} companies — ` +
      `run \`npm run map-template\` (a smaller template truncates a class silently)`,
  );
}

const pies = readFileSync(resolve(process.cwd(), "src/MarketSharePies.tsx"), "utf8");
const paletteBlock = /const PALETTE = \[([\s\S]*?)\];/.exec(pies);
const palette = paletteBlock ? (paletteBlock[1].match(/#[0-9a-fA-F]{6}/g) ?? []).length : 0;
if (palette < max) {
  problems.push(
    `MarketSharePies PALETTE has ${palette} colours but limits.json allows ${max} companies — ` +
      `companies would share a hue`,
  );
}

const api = readFileSync(resolve(process.cwd(), "src/api.ts"), "utf8");
if (!api.includes('request<Limits>("/limits")')) {
  problems.push(
    "api.ts does not read the limits from /api/limits — the console must not carry its own numbers",
  );
}

if (problems.length) {
  console.error("\ncheck-limits: the copies disagree with limits.json\n");
  for (const problem of problems) console.error("  - " + problem);
  console.error(
    `\nlimits.json is the single authority (classSize ${limits.classSize.min}..${max}, ` +
      `default ${limits.classSize.default}). Change it there, then regenerate what derives from it.\n`,
  );
  process.exit(1);
}
console.log(`check-limits: map slots ${max}/${max}, palette ${palette} colours, console reads /api/limits`);
