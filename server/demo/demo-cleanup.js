#!/usr/bin/env node
"use strict";
/*
 * Codenvi demo cleanup: removes everything demo-seed.js created, and nothing else.
 *
 *   node demo-cleanup.js              delete the demo data
 *   node demo-cleanup.js --dry-run    only count what would be deleted
 *
 * Only rows whose id starts with 5eed0000- are touched (real ids are random UUIDs). Deleting the demo PCs also
 * deletes their projects, sessions, messages, jobs and commits (the database cascades). Your real installs, PCs,
 * projects, pairings and tokens are not affected. All deletes run in one transaction: all or nothing.
 *
 * CONFIG (all optional): DATABASE_URL, or ENV_FILE (default /var/www/codenvi/shared/.env).
 * After running it, open the phone app (or tap reload); it drops the demo PCs on its next sync. The demo keys stay in
 * the phone's key store; they are harmless, and "Remove" on the device (if offered) clears them.
 */
const fs = require("node:fs");
const path = require("node:path");
const { createRequire } = require("node:module");

const LIKE = "5eed0000-%";

function loadEnv(file) {
  const out = {};
  for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
    const m = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/.exec(line);
    if (!m || line.trimStart().startsWith("#")) continue;
    out[m[1]] = m[2].replace(/^(['"])(.*)\1$/, "$2");
  }
  return out;
}

function connect() {
  const envFile = process.env.ENV_FILE || "/var/www/codenvi/shared/.env";
  let url = process.env.DATABASE_URL;
  if (!url) {
    if (!fs.existsSync(envFile)) throw new Error(`Cannot find ${envFile}. Set ENV_FILE=/var/www/<name>/shared/.env or DATABASE_URL.`);
    url = loadEnv(envFile).DATABASE_URL;
    if (!url) throw new Error(`DATABASE_URL is not set in ${envFile}.`);
  }
  const candidates = [path.join(path.dirname(envFile), "..", "current", "package.json"), path.join(process.cwd(), "package.json"), __filename];
  let Client;
  for (const c of candidates) {
    try { ({ Client } = createRequire(c)("pg")); break; } catch { /* try the next */ }
  }
  if (!Client) throw new Error("Cannot load the 'pg' package. Run: NODE_PATH=/var/www/<name>/current/node_modules node demo-cleanup.js");
  return new Client({ connectionString: url });
}

const COUNTS = [
  ["PCs", "SELECT count(*) FROM devices WHERE id::text LIKE $1"],
  ["projects", "SELECT count(*) FROM projects WHERE id::text LIKE $1"],
  ["sessions", "SELECT count(*) FROM sessions WHERE id::text LIKE $1"],
  ["messages", "SELECT count(*) FROM messages WHERE id::text LIKE $1"],
  ["jobs", "SELECT count(*) FROM jobs WHERE id::text LIKE $1"],
  ["commits", "SELECT count(*) FROM commits WHERE id::text LIKE $1"],
  ["demo installs", "SELECT count(*) FROM installs WHERE id::text LIKE $1"],
];

async function main() {
  const dryRun = process.argv.includes("--dry-run");
  const c = connect();
  await c.connect();
  try {
    const found = [];
    for (const [label, q] of COUNTS) found.push([label, Number((await c.query(q, [LIKE])).rows[0].count)]);
    const total = found.reduce((a, [, n]) => a + n, 0);
    console.log("Demo data found: " + found.map(([l, n]) => `${n} ${l}`).join(", "));
    if (total === 0) { console.log("Nothing to remove."); return; }
    if (dryRun) { console.log("Dry run, nothing deleted."); return; }

    await c.query("BEGIN");
    // Order matters: devices cascade to projects, sessions, messages, jobs and commits; installs are referenced by devices.
    await c.query("DELETE FROM devices WHERE id::text LIKE $1", [LIKE]);
    // Safety net for anything seeded that a cascade did not reach (should be zero rows).
    await c.query("DELETE FROM commits WHERE id::text LIKE $1", [LIKE]);
    await c.query("DELETE FROM jobs WHERE id::text LIKE $1", [LIKE]);
    await c.query("DELETE FROM messages WHERE id::text LIKE $1", [LIKE]);
    await c.query("DELETE FROM sessions WHERE id::text LIKE $1", [LIKE]);
    await c.query("DELETE FROM projects WHERE id::text LIKE $1", [LIKE]);
    await c.query("DELETE FROM installs WHERE id::text LIKE $1", [LIKE]);

    let left = 0;
    for (const [, q] of COUNTS) left += Number((await c.query(q, [LIKE])).rows[0].count);
    if (left !== 0) throw new Error(`${left} demo rows are still present; rolling back.`);
    await c.query("COMMIT");
    console.log("Demo data removed. Open the phone app (or tap reload) so it syncs; the demo PCs disappear.");
  } catch (e) {
    await c.query("ROLLBACK").catch(() => {});
    throw e;
  } finally {
    await c.end();
  }
}

main().catch((e) => { console.error(`Cleanup failed, nothing was changed: ${e.message}`); process.exit(1); });
