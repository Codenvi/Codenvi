#!/usr/bin/env node
"use strict";
/*
 * Codenvi demo seed: fills the database with fake PCs, projects, sessions, replies, jobs and commits so you can
 * take screenshots for a demo video. Undo it with demo-cleanup.js.
 *
 *   node demo-seed.js              insert the demo data (running it again first removes the previous demo data)
 *   node demo-seed.js --dry-run    build everything and print a summary, without touching the database
 *
 * SAFE BY DESIGN
 *   - Every row it creates has an id that starts with 5eed0000- (real ids are random UUIDs and never look like that).
 *     demo-cleanup.js deletes only those ids. Your real installs, PCs, projects, pairings and tokens are never touched.
 *   - The fake PCs belong to fake "installs" that have no tokens, so nothing can sign in as them.
 *   - It never reads, changes or deletes any existing row.
 *
 * WHAT YOU GET (3 PCs, 7 projects, 15 sessions, 34 encrypted messages, 16 jobs in several states, 21 commits)
 *   The text is encrypted for real (docs/crypto.md), with a demo key derived from each fake PC's id, so the phone can
 *   read it once it has that key. At the end the script prints one key string per fake PC. Paste each into the phone:
 *   Devices -> the PC -> "Paste a key string" (or open the codenvi://key... string on the phone).
 *   The demo key protects nothing real; it is derived from public ids.
 *
 * CONFIG (all optional)
 *   DATABASE_URL   connection string; default: read from the app's .env file
 *   ENV_FILE       path of that .env; default /var/www/codenvi/shared/.env (use /var/www/<name>/shared/.env if your
 *                  CODENVI_APP_NAME is not "codenvi")
 *
 * RUN (on the VPS, as a user that can read the .env file, usually root or the deploy user):
 *   node demo-seed.js
 */
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { createRequire } = require("node:module");

const SEED_PREFIX = "5eed0000-";
const KEY_VERSION = 1;

// ----------------------------------------------------------------------------------------------- ids and crypto
const KIND = { install: 1, device: 2, project: 3, session: 4, message: 5, job: 6, commit: 7 };
const seedId = (kind, n) => `${SEED_PREFIX}${KIND[kind].toString(16).padStart(4, "0")}-4000-8000-${n.toString(16).padStart(12, "0")}`;

const B32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
function base32(buf, bitCount = buf.length * 8) {
  let out = "", buffer = 0, bits = 0, used = 0;
  for (const b of buf) {
    for (let i = 7; i >= 0 && used < bitCount; i--, used++) {
      buffer = (buffer << 1) | ((b >> i) & 1);
      if (++bits === 5) { out += B32[buffer]; buffer = 0; bits = 0; }
    }
  }
  if (bits > 0) out += B32[buffer << (5 - bits)];
  return out;
}
const sha256 = (b) => crypto.createHash("sha256").update(b).digest();
/** 56 symbols without hyphens: Base32(key) + Base32(first 20 bits of SHA-256(key)). */
const keyCompact = (key) => base32(key) + base32(sha256(key), 20);
const keyCheck = (key) => crypto.createHmac("sha256", key).update("codenvi-kcv-v1").digest().subarray(0, 8);
const encKeyFor = (key, deviceId) =>
  Buffer.from(crypto.hkdfSync("sha256", key, Buffer.from(deviceId, "utf8"), Buffer.from("codenvi-v1-enc", "utf8"), 32));

/** docs/crypto.md §5-6: version | keyVersion(2, BE) | nonce(12) | ciphertext | tag(16). */
function encrypt(key, deviceId, objectType, objectId, direction, createdAtMs, plain, nonce = crypto.randomBytes(12)) {
  const aad = Buffer.from(`v1|${deviceId}|${objectType}|${objectId}|${direction}|${createdAtMs}`, "utf8");
  const cipher = crypto.createCipheriv("aes-256-gcm", encKeyFor(key, deviceId), nonce, { authTagLength: 16 });
  cipher.setAAD(aad);
  const body = Buffer.concat([cipher.update(Buffer.from(JSON.stringify(plain), "utf8")), cipher.final()]);
  const head = Buffer.from([1, KEY_VERSION >> 8, KEY_VERSION & 255]);
  return Buffer.concat([head, nonce, body, cipher.getAuthTag()]);
}

// ---------------------------------------------------------------------------------------------------- demo content
// ageMin: how long ago the prompt was sent. state: done | unseen (finished a moment ago) | running | queued.
const T = (prompt, reply, extra = {}) => ({ prompt, reply, ageMin: 600, state: "done", files: 3, ins: 48, del: 12, model: "sonnet", effort: "high", ...extra });

const DEVICES = [
  {
    name: "Home Desktop",
    projects: [
      { name: "Atlas API", sessions: [
        { name: "Rate limiting", turns: [
          T("Add per-user rate limiting to the public endpoints. Keep it configurable.",
            "Added a token-bucket limiter in `src/middleware/rateLimit.ts` and wired it to `/v1/*`.\n\n- Limits are read from `RATE_LIMIT_PER_MIN` (default 120)\n- Responds with `429` and a `Retry-After` header\n- 6 new tests, all passing\n\n```ts\napp.use('/v1', rateLimit({ perMinute: env.RATE_LIMIT_PER_MIN }));\n```", { ageMin: 300, files: 4, ins: 96, del: 8, commit: "Add configurable per-user rate limiting" }),
          T("Now log every throttled request with the user id.",
            null, { ageMin: 1.5, state: "running", progress: ["Reading src/middleware/rateLimit.ts", "Editing src/middleware/rateLimit.ts", "Running npm test", "Fixing 1 failing test in rateLimit.test.ts"] }),
        ] },
        { name: "Fix auth bug", turns: [
          T("Users get logged out after about 15 minutes even with activity. Find out why.",
            "Found it: the refresh call was only triggered on page load, never on activity.\n\n**Fix:** refresh the access token when less than 60 s remain, on any API call.\n\n| File | Change |\n|---|---|\n| `auth/session.ts` | proactive refresh |\n| `auth/session.test.ts` | 3 new cases |\n\nAll 41 tests pass.", { ageMin: 1500, files: 2, ins: 37, del: 5, model: "opus", commit: "Refresh access token proactively on activity" }),
        ] },
        { name: "Write docs", turns: [
          T("Document the REST API in docs/api.md with curl examples.",
            "Created `docs/api.md` covering 14 endpoints, each with a curl example and an example response. I also added a short authentication section at the top.", { ageMin: 3000, files: 1, ins: 210, del: 0, model: "haiku", effort: "medium", commit: "Document the REST API" }),
        ] },
      ] },
      { name: "Pixel Garden", sessions: [
        { name: "Add dark mode", turns: [
          T("Add a dark mode toggle to the settings screen and remember the choice.",
            "Dark mode is in.\n\n1. New `ThemeProvider` with `light` / `dark` / `system`\n2. Toggle on the Settings screen\n3. Choice is saved to local storage and applied before first paint, so there is no flash\n\nScreens checked: Home, Garden, Settings.", { ageMin: 2, state: "unseen", files: 6, ins: 142, del: 31, model: "opus", commit: "Add dark mode with system default" }),
        ] },
        { name: "Performance", turns: [
          T("The garden view drops frames when there are more than 200 plants. Profile it and fix it.",
            "The cost was re-rendering every plant on each tick. I memoised the plant sprites and moved animation to a single `requestAnimationFrame` loop.\n\n- 200 plants: 24 fps → 60 fps\n- 1000 plants: 6 fps → 41 fps", { ageMin: 700, files: 5, ins: 88, del: 64, model: "opus", effort: "max", commit: "Render plants from one animation loop" }),
        ] },
      ] },
      { name: "Dotfiles", sessions: [
        { name: "Shell setup", turns: [
          T("Make my shell setup work on a fresh machine with one command.",
            "Added `bootstrap.sh` that installs the packages, links the config files and is safe to run twice. Tested in a clean container.", { ageMin: 5000, files: 3, ins: 74, del: 2, model: "haiku", effort: "low", commit: "Add idempotent bootstrap script" }),
        ] },
      ] },
    ],
  },
  {
    name: "Work Laptop",
    projects: [
      { name: "Checkout Web App", sessions: [
        { name: "Stripe webhooks", turns: [
          T("Handle the Stripe `invoice.paid` and `invoice.payment_failed` webhooks and update the order status.",
            "Both webhooks are handled in `api/webhooks/stripe.ts`.\n\n- Signature verified with the signing secret\n- Idempotent: repeated events are ignored using the event id\n- `invoice.payment_failed` marks the order `past_due` and queues the reminder email\n\n```\n12 passing (stripe.test.ts)\n```", { ageMin: 4, state: "unseen", files: 5, ins: 164, del: 9, model: "opus", effort: "xhigh", commit: "Handle Stripe invoice webhooks" }),
        ] },
        { name: "Accessibility pass", turns: [
          T("Run an accessibility pass on the checkout form and fix what you find.",
            "Fixed 9 issues: missing labels, low-contrast error text, focus order on the address step and no `aria-live` region for validation errors. The form is now fully keyboard-operable.", { ageMin: 1900, files: 7, ins: 58, del: 33, commit: "Fix checkout form accessibility issues" }),
        ] },
        { name: "Bundle size", turns: [
          T("Why is the main bundle 1.8 MB? Cut it down.",
            "Two culprits: a full date library and an unused chart package. Replaced the first with a 2 kB helper, lazy-loaded the second.\n\n**1.8 MB → 640 kB** (gzip 210 kB).", { ageMin: 2600, files: 4, ins: 31, del: 22, model: "sonnet", commit: "Trim the main bundle by 64%" }),
        ] },
      ] },
      { name: "Mobile Client", sessions: [
        { name: "Offline mode", turns: [
          T("Let the app queue messages while offline and send them when the connection is back.",
            "Added an outbox stored in the local database. Messages show a clock icon while queued and are sent in order on reconnect, with retry and back-off.", { ageMin: 8, state: "unseen", files: 8, ins: 230, del: 40, model: "opus", commit: "Queue messages while offline" }),
        ] },
        { name: "Crash on launch", turns: [
          T("Crash on launch on Android 8. Here is the stack trace: NullPointerException in ThemeStore.load",
            "`ThemeStore.load` read a preference that does not exist on first launch. It now falls back to the default and I added a regression test.", { ageMin: 3200, files: 2, ins: 14, del: 3, model: "haiku", effort: "medium", commit: "Fix launch crash on first run" }),
        ] },
      ] },
    ],
  },
  {
    name: "Build Server",
    projects: [
      { name: "CI Pipelines", sessions: [
        { name: "Cache dependencies", turns: [
          T("Cache the package manager and build outputs in CI so builds are faster.",
            "Added dependency and build caching keyed on the lockfile hash. Median build time: **9m 40s → 3m 15s**.", { ageMin: 2100, files: 2, ins: 26, del: 4, model: "sonnet", commit: "Cache dependencies and build output in CI" }),
        ] },
        { name: "Nightly security scan", turns: [
          T("Add a nightly job that scans dependencies and opens an issue if it finds a high severity problem.",
            null, { ageMin: 25, state: "queued", model: "sonnet", effort: "high" }),
        ] },
      ] },
      { name: "Data Warehouse", sessions: [
        { name: "Slow report query", turns: [
          T("The weekly revenue report takes 4 minutes. Make it faster without changing the result.",
            "The query scanned the whole `orders` table. A partial index on `status = 'paid'` plus rewriting a correlated subquery as a join brought it to **11 seconds**. Output verified identical on last quarter's data.", { ageMin: 6, state: "unseen", files: 3, ins: 47, del: 29, model: "opus", effort: "high", commit: "Speed up the weekly revenue report" }),
        ] },
        { name: "Backfill", turns: [
          T("Write a safe backfill script for the new `currency` column, in batches.",
            "`backfill_currency.sql` updates 10 000 rows per batch, commits between batches and can be stopped and resumed. Dry-run mode prints what it would change.", { ageMin: 4200, files: 1, ins: 58, del: 0, model: "sonnet", commit: "Add resumable currency backfill" }),
        ] },
      ] },
    ],
  },
];

// ------------------------------------------------------------------------------------------------------ builders
function build(now) {
  const installs = [], devices = [], projects = [], sessions = [], messages = [], jobs = [], commits = [];
  const keys = [];
  let nInstall = 0, nDevice = 0, nProject = 0, nSession = 0, nMessage = 0, nJob = 0, nCommit = 0;
  const at = (minAgo) => new Date(now - Math.round(minAgo * 60_000));

  for (const dev of DEVICES) {
    const installId = seedId("install", ++nInstall);
    const deviceId = seedId("device", ++nDevice);
    const key = sha256(Buffer.from(`codenvi-demo-seed|${deviceId}`, "utf8"));
    keys.push({ deviceId, name: dev.name, key });
    installs.push({ id: installId, name: dev.name, lastSeen: at(3 + nDevice * 4), paired: at(60 * 24 * 14) });
    devices.push({ id: deviceId, installId, name: dev.name, keyCheck: keyCheck(key), lastOnline: at(3 + nDevice * 4), created: at(60 * 24 * 14) });

    dev.projects.forEach((proj, pIdx) => {
      const projectId = seedId("project", ++nProject);
      const sessionRows = [];
      let latest = 60 * 24 * 7;

      for (const sess of proj.sessions) {
        const sessionId = seedId("session", ++nSession);
        let seq = 0;
        let sessLatest = 60 * 24 * 7;
        for (const turn of sess.turns) {
          const promptTime = at(turn.ageMin);
          const jobId = seedId("job", ++nJob);
          const promptId = seedId("message", ++nMessage);
          seq += 1;
          messages.push({
            id: promptId, sessionId, seq, role: "user", jobId, created: promptTime,
            ciphertext: encrypt(key, deviceId, "message", promptId, "p2d", promptTime.getTime(),
              { text: turn.prompt, projectId, sessionId, model: turn.model, effort: turn.effort }),
          });

          const job = { id: jobId, deviceId, projectId, sessionId, promptMessageId: promptId, model: turn.model, effort: turn.effort,
            status: "succeeded", queued: promptTime, delivered: null, started: null, finished: null };
          sessLatest = Math.min(sessLatest, turn.ageMin);

          if (turn.state === "queued") {
            job.status = "queued";
          } else if (turn.state === "running") {
            job.status = "running";
            job.delivered = new Date(promptTime.getTime() + 2_000);
            job.started = new Date(promptTime.getTime() + 5_000);
            turn.progress.forEach((line, i) => {
              const t = new Date(promptTime.getTime() + 15_000 + i * 15_000);
              const id = seedId("message", ++nMessage);
              seq += 1;
              messages.push({ id, sessionId, seq, role: "progress", jobId, created: t,
                ciphertext: encrypt(key, deviceId, "progress", id, "d2p", t.getTime(), { line }) });
            });
          } else {
            const duration = 40_000 + ((nJob * 7919) % 160_000);
            job.delivered = new Date(promptTime.getTime() + 2_000);
            job.started = new Date(promptTime.getTime() + 5_000);
            job.finished = new Date(promptTime.getTime() + 5_000 + duration);
            const replyTime = job.finished;
            const replyId = seedId("message", ++nMessage);
            const commitId = seedId("commit", ++nCommit);
            const gitHash = crypto.createHash("sha1").update(`demo-commit-${commitId}`).digest("hex");
            seq += 1;
            messages.push({
              id: replyId, sessionId, seq, role: "assistant", jobId, created: replyTime,
              ciphertext: encrypt(key, deviceId, "message", replyId, "d2p", replyTime.getTime(), {
                text: turn.reply, costUsd: Math.round((0.04 + (nJob % 9) * 0.031) * 1000) / 1000, durationMs: duration,
                commitHash: gitHash, filesChanged: turn.files, part: 1, parts: 1 }),
            });
            commits.push({
              id: commitId, projectId, jobId, gitHash, kind: "task", created: replyTime,
              encMeta: encrypt(key, deviceId, "commit.meta", commitId, "meta", 0,
                { message: turn.commit, filesChanged: turn.files, insertions: turn.ins, deletions: turn.del }),
            });
          }
          jobs.push(job);
        }
        latest = Math.min(latest, sessLatest);
        sessionRows.push({ id: sessionId, projectId, name: sess.name, created: at(60 * 24 * 7), updated: at(sessLatest) });
      }

      // A baseline commit per project, the state before Codenvi touched it.
      const baseId = seedId("commit", ++nCommit);
      commits.push({
        id: baseId, projectId, jobId: null, kind: "baseline", created: at(60 * 24 * 7 + 5),
        gitHash: crypto.createHash("sha1").update(`demo-baseline-${baseId}`).digest("hex"),
        encMeta: encrypt(key, deviceId, "commit.meta", baseId, "meta", 0,
          { message: "Baseline before Codenvi", filesChanged: 0, insertions: 0, deletions: 0 }),
      });

      for (const s of sessionRows) {
        sessions.push({ ...s, encMeta: encrypt(key, deviceId, "session.meta", s.id, "meta", 0, { name: s.name }) });
      }
      projects.push({
        id: projectId, deviceId, defaultSessionId: sessionRows[0].id, sortOrder: pIdx,
        encMeta: encrypt(key, deviceId, "project.meta", projectId, "meta", 0, { name: proj.name, devBranch: "codenvi-dev" }),
        created: at(60 * 24 * 7 + 10), updated: at(latest),
      });
    });
  }
  return { installs, devices, projects, sessions, messages, jobs, commits, keys };
}

// ------------------------------------------------------------------------------------------------------ database
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
  // Use the pg package that the deployed server already has (…/<app>/current/node_modules).
  const candidates = [path.join(path.dirname(envFile), "..", "current", "package.json"), path.join(process.cwd(), "package.json"), __filename];
  let Client;
  for (const c of candidates) {
    try { ({ Client } = createRequire(c)("pg")); break; } catch { /* try the next */ }
  }
  if (!Client) throw new Error("Cannot load the 'pg' package. Run: NODE_PATH=/var/www/<name>/current/node_modules node demo-seed.js");
  return new Client({ connectionString: url });
}

async function insertAll(c, d) {
  // Start clean: remove demo rows from an earlier run (only rows whose id starts with the seed prefix).
  await c.query("DELETE FROM devices WHERE id::text LIKE $1", [`${SEED_PREFIX}%`]);
  await c.query("DELETE FROM installs WHERE id::text LIKE $1", [`${SEED_PREFIX}%`]);

  for (const i of d.installs) {
    await c.query(
      `INSERT INTO installs (id, kind, name, app_version, app_build, os_version, device_model, paired_at, last_seen_at)
       VALUES ($1, 'desktop', $2, '1.0.18', 18, 'Windows 11', NULL, $3, $4)`, [i.id, i.name, i.paired, i.lastSeen]);
  }
  for (const x of d.devices) {
    await c.query(
      `INSERT INTO devices (id, install_id, name, key_version, key_check, last_online_at, created_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7)`, [x.id, x.installId, x.name, KEY_VERSION, x.keyCheck, x.lastOnline, x.created]);
  }
  for (const p of d.projects) {
    await c.query(
      `INSERT INTO projects (id, device_id, enc_meta, default_session_id, sort_order, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7)`, [p.id, p.deviceId, p.encMeta, p.defaultSessionId, p.sortOrder, p.created, p.updated]);
  }
  for (const s of d.sessions) {
    await c.query(
      `INSERT INTO sessions (id, project_id, enc_meta, created_at, updated_at) VALUES ($1, $2, $3, $4, $5)`,
      [s.id, s.projectId, s.encMeta, s.created, s.updated]);
  }
  for (const m of d.messages) {
    await c.query(
      `INSERT INTO messages (id, session_id, seq, role, job_id, ciphertext, client_created_at, created_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $7)`, [m.id, m.sessionId, m.seq, m.role, m.jobId, m.ciphertext, m.created]);
  }
  for (const j of d.jobs) {
    await c.query(
      `INSERT INTO jobs (id, device_id, project_id, session_id, prompt_message_id, kind, model, effort, status,
                         queued_at, delivered_at, started_at, finished_at)
       VALUES ($1, $2, $3, $4, $5, 'prompt', $6, $7, $8, $9, $10, $11, $12)`,
      [j.id, j.deviceId, j.projectId, j.sessionId, j.promptMessageId, j.model, j.effort, j.status, j.queued, j.delivered, j.started, j.finished]);
  }
  for (const k of d.commits) {
    await c.query(
      `INSERT INTO commits (id, project_id, job_id, git_hash, kind, enc_meta, created_at) VALUES ($1, $2, $3, $4, $5, $6, $7)`,
      [k.id, k.projectId, k.jobId, k.gitHash, k.kind, k.encMeta, k.created]);
  }
}

async function main() {
  const dryRun = process.argv.includes("--dry-run");
  const data = build(Date.now());
  const summary = `${data.devices.length} PCs, ${data.projects.length} projects, ${data.sessions.length} sessions, ` +
    `${data.messages.length} messages, ${data.jobs.length} jobs, ${data.commits.length} commits`;

  if (dryRun) {
    console.log(`Dry run, nothing written. Would create: ${summary}.`);
  } else {
    const c = connect();
    await c.connect();
    try {
      await c.query("BEGIN");
      await insertAll(c, data);
      await c.query("COMMIT");
    } catch (e) {
      await c.query("ROLLBACK").catch(() => {});
      throw e;
    } finally {
      await c.end();
    }
    console.log(`Demo data inserted: ${summary}.`);
  }

  console.log("\nGive the phone the demo keys (Devices -> the PC -> Paste a key string), one per demo PC:\n");
  for (const k of data.keys) {
    const dn = encodeURIComponent(k.name).replace(/\+/g, "%20");
    console.log(`  ${k.name}\n  codenvi://key?dev=${k.deviceId}&kv=${KEY_VERSION}&k=${keyCompact(k.key)}&dn=${dn}\n`);
  }
  console.log("Then open the app (or tap reload) so it syncs. The demo PCs show as offline: the server only marks a PC online\n" +
    "while that PC is connected. Replies marked unseen appear as white dots on the phone.\n" +
    "Remove everything with: node demo-cleanup.js");
}

if (require.main === module) {
  main().catch((e) => { console.error(`Seed failed, nothing was changed: ${e.message}`); process.exit(1); });
}
module.exports = { base32, keyCompact, keyCheck, encKeyFor, encrypt, build, seedId };
