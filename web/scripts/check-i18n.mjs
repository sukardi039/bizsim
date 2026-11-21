#!/usr/bin/env node
/**
 * D116: no user-readable English may bypass the dictionaries.
 *
 * A trainee in a Chinese class read the console with an English "sign out" in its header, and the
 * facilitator's screen said "Run 1" and "LIVE" while everything around them was Chinese. The class
 * language was resolving correctly — the strings were literals that never went through `t()`.
 *
 * This walks the real JSX (TypeScript's own parser, so multi-line text and nested expressions cannot
 * fool it) and reports every text node that a person could read. It is wired into `npm run build`, so a
 * literal cannot reach a class again.
 *
 * Usage:  node scripts/check-i18n.mjs [--report]
 *         --report prints violations and exits 0 (for taking stock)
 */
import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import ts from "typescript";

const ROOT = new URL("..", import.meta.url).pathname;
const SRC = join(ROOT, "src");
const REPORT_ONLY = process.argv.includes("--report");

/**
 * Text allowed to stay as it is. Brand and punctuation, not words a translator would ever touch.
 * Everything else must come from `t("key")` in both catalogues.
 */
const ALLOWED = new Set([
  "bizsim",
  "bizsim ·",
  "·",
  "|",
  "—",
  "→",
  "✕",
  "/",
  "· ",
]);

const files = [];
(function walk(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) walk(path);
    else if (entry.name.endsWith(".tsx")) files.push(path);
  }
})(SRC);

const violations = [];
for (const path of files) {
  const text = readFileSync(path, "utf8");
  const source = ts.createSourceFile(path, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const lineOf = (node) =>
    source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1;

  (function visit(node) {
    if (ts.isJsxText(node)) {
      const raw = node.getText(source).trim();
      const cleaned = raw.replace(/\s+/g, " ");
      // Two or more Latin letters is a word; a lone "·" or punctuation is not.
      const hasWords = /[A-Za-z]{2,}/.test(cleaned);
      if (cleaned && hasWords && !ALLOWED.has(cleaned)) {
        violations.push({ file: relative(ROOT, path), line: lineOf(node), text: cleaned });
      }
    }
    ts.forEachChild(node, visit);
  })(source);
}

const grouped = new Map();
for (const v of violations) {
  const key = v.file;
  grouped.set(key, [...(grouped.get(key) ?? []), v]);
}

if (grouped.size === 0) {
  console.log(`check-i18n: ${files.length} files, no untranslated text`);
  process.exit(0);
}

for (const [file, rows] of grouped) {
  console.log(`\n${file}`);
  for (const row of rows) console.log(`  ${row.line}: ${JSON.stringify(row.text)}`);
}
console.log(
  `\ncheck-i18n: ${violations.length} untranslated text node(s) in ${grouped.size} file(s).` +
    `\nPut each through the dictionary (t("...")) with both an English and a Chinese entry.`,
);
process.exit(REPORT_ONLY ? 0 : 1);
