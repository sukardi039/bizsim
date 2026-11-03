// Writes dist/build.json: identifies the *web* bundle that is actually being served.
//
// /api/version proves the API image; this proves the console, and they are different containers —
// a deploy that rebuilt only one of them looked like "nothing happened".
import { writeFileSync } from "node:fs";
import { execSync } from "node:child_process";

let commit = process.env.GIT_COMMIT ?? "";
if (!commit) {
  try {
    commit = execSync("git rev-parse --short HEAD", { stdio: ["ignore", "pipe", "ignore"] })
      .toString()
      .trim();
  } catch {
    commit = ""; // no .git in the build context: the timestamp still identifies the build
  }
}

const builtAt = new Date().toISOString();
writeFileSync("dist/build.json", JSON.stringify({ builtAt, commit }, null, 2) + "\n");
console.log(`dist/build.json: ${commit || "(no commit)"} ${builtAt}`);
