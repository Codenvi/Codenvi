#!/usr/bin/env node
"use strict";
/*
 * Codenvi demo data: fills the database with a realistic week of work (fake PCs, phones, projects, sessions, replies,
 * jobs in every state, commits) so every page of the Android app shows something, for screenshots and demo videos.
 * Undo it with demo-cleanup.js.
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
 * WHAT YOU GET (3 PCs, 3 phones, 7 projects, 20 sessions, encrypted messages, jobs in every state, commits)
 *   - Devices: 3 PCs (the ones with a running job show the "Running" chip); Settings: 2 phones, 3 PCs and 1 revoked phone.
 *   - Projects: script buttons (one idle, one running), a default session that is not always the first.
 *   - Sessions / Chat / Multimode: replies you have not seen yet, a running job with live progress, a queued job, a job
 *     waiting for the Claude sign-in, a failed job with its error, a cancelled job, Markdown replies with tables and code.
 *   - Commits: baseline, commits made from the phone, and a rollback that reverts an earlier commit.
 *   - Code changes page, Memory tab, and the Commit / Pull / Publish buttons: these read live from a PC, so they come from
 *     demo-pc.js (run it after this script; it brings the demo PCs online and answers the phone with the data defined
 *     below). Not seeded at all: the Commit / Pull / Publish markers in a chat (they are kept on the phone).
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
// A realistic week of work on three PCs, built to fill every page of the Android app (Devices, Projects, Sessions, Chat,
// Multimode, Commits, Settings) and every state the app can show:
//   - replies you have not seen yet (white dots / counts), a running job with live progress on two PCs ("Running" chips),
//     a queued job, a job waiting for the Claude sign-in, a failed job with its error, a cancelled job;
//   - projects with a script button (idle and running), a default session that is not the first one;
//   - Markdown replies (lists, tables, code blocks, a long one), several turns per session;
//   - commits: baseline, commits made from the phone with a typed message, and a rollback that reverts earlier commits.
// Replies carry no commit hash: since Codenvi stopped committing automatically, work stays uncommitted until you press Commit.
//
// Turn state (ageMin = how long ago the prompt was sent):
//   done (default) | unseen (finished a moment ago) | running | queued | waiting | failed | cancelled

const T = (prompt, reply, extra = {}) => ({ prompt, reply, ageMin: 600, state: "done", files: 3, model: "sonnet", effort: "high", ...extra });
const C = (ageMin, message, files, ins, del, extra = {}) => ({ ageMin, message, files, ins, del, kind: "task", ...extra });

const DEVICES = [
  {
    name: "Home Desktop", os: "Windows 11 Pro 24H2", seenMin: 4,
    projects: [
      {
        name: "Atlas API", script: "idle", defaultSession: 0,
        git: { ahead: 3, behind: 0,
          changes: [["U", "src/middleware/rateLimit.ts"],
            ["U", "src/middleware/rateLimit.test.ts"],
            ["N", "src/middleware/throttleLogger.ts"],
            ["U", "src/config/env.ts"],
            ["N", "docs/rate-limits.md"],
            ["U", "docs/api.md"],
            ["D", "src/legacy/oldLimiter.ts"],
            ["U", "package.json"],
            ["N", "src/modules/billing/webhooks/handlers/subscription/invoicePaymentFailedHandler.test.ts"]],
          claudeMd: "# Atlas API\n\nTypeScript REST API on Express and PostgreSQL.\n\n## Commands\n- `npm run dev` - start with reload\n- `npm test` - run the tests (vitest)\n\n## Rules\n- Keep handlers thin; put logic in `src/modules/*`.\n- Every endpoint needs a test and an entry in `docs/api.md`.\n" },
        sessions: [
          { name: "Rate limiting", turns: [
            T("Add per-user rate limiting to the public endpoints. Keep it configurable.",
              "Added a token-bucket limiter in `src/middleware/rateLimit.ts` and wired it to `/v1/*`.\n\n- Limits are read from `RATE_LIMIT_PER_MIN` (default 120)\n- Responds with `429` and a `Retry-After` header\n- 6 new tests, all passing\n\n```ts\napp.use('/v1', rateLimit({ perMinute: env.RATE_LIMIT_PER_MIN }));\n```\n\nThe changes are not committed yet.",
              { ageMin: 320, files: 4 }),
            T("Good. Different limits for the `/v1/export` endpoints please, they are heavier.",
              "Done. `rateLimit` now accepts a per-route override, and `/v1/export/*` uses `EXPORT_RATE_LIMIT_PER_MIN` (default 10).\n\n| Route | Default limit |\n|---|---|\n| `/v1/*` | 120 / min |\n| `/v1/export/*` | 10 / min |\n\nI added a test that checks the two buckets do not share a counter.",
              { ageMin: 280, files: 3 }),
            T("Now log every throttled request with the user id.", null,
              { ageMin: 1.5, state: "running", progress: ["Reading src/middleware/rateLimit.ts", "Editing src/middleware/rateLimit.ts", "Running: npm test", "Fixing 1 failing test in rateLimit.test.ts"] }),
          ] },
          { name: "Fix auth bug", turns: [
            T("Users get logged out after about 15 minutes even with activity. Find out why.",
              "Found it: the refresh call was only triggered on page load, never on activity.\n\n**Fix:** refresh the access token when less than 60 s remain, on any API call.\n\n| File | Change |\n|---|---|\n| `auth/session.ts` | proactive refresh |\n| `auth/session.test.ts` | 3 new cases |\n\nAll 41 tests pass.",
              { ageMin: 1500, files: 2, model: "opus" }),
          ] },
          { name: "Write docs", turns: [
            T("Document the REST API in docs/api.md with curl examples.",
              "Created `docs/api.md` covering 14 endpoints, each with a curl example and an example response. I also added a short authentication section at the top.",
              { ageMin: 3000, files: 1, model: "haiku", effort: "medium" }),
          ] },
        ],
        commits: [
          C(60 * 24 * 7 + 5, "Baseline before Codenvi", 0, 0, 0, { kind: "baseline" }),
          C(3010, "Document the REST API", 1, 210, 0),
          C(1490, "Refresh access token proactively on activity", 2, 37, 5),
        ],
      },
      {
        name: "Pixel Garden", script: "running", defaultSession: 1,
        git: { ahead: 1, behind: 2,
          changes: [["N", "src/theme/ThemeProvider.tsx"],
            ["N", "src/theme/useTheme.ts"],
            ["U", "src/screens/Settings.tsx"],
            ["U", "src/index.css"],
            ["U", "README.md"],
            ["D", "src/styles/legacy.css"]],
          claudeMd: "# Pixel Garden\n\nA cosy plant-growing game in React.\n\n## Commands\n- `npm run dev` - local server on port 3000\n- `npm run build` - production build\n\n## Style\n- Function components and hooks only.\n- No new dependencies without asking.\n" },
        sessions: [
          { name: "Add dark mode", turns: [
            T("Add a dark mode toggle to the settings screen and remember the choice.",
              "Dark mode is in.\n\n1. New `ThemeProvider` with `light` / `dark` / `system`\n2. Toggle on the Settings screen\n3. Choice is saved to local storage and applied before first paint, so there is no flash\n\nScreens checked: Home, Garden, Settings.",
              { ageMin: 2, state: "unseen", files: 6, model: "opus" }),
          ] },
          { name: "Performance", turns: [
            T("The garden view drops frames when there are more than 200 plants. Profile it and fix it.",
              "The cost was re-rendering every plant on each tick. I memoised the plant sprites and moved animation to a single `requestAnimationFrame` loop.\n\n- 200 plants: 24 fps → 60 fps\n- 1000 plants: 6 fps → 41 fps\n\n```js\nfunction frame(t) {\n  for (const p of plants) p.update(t);\n  draw(plants);\n  requestAnimationFrame(frame);\n}\n```",
              { ageMin: 700, files: 5, model: "opus", effort: "max" }),
            T("Try rendering the plants on a canvas instead of DOM nodes and compare.",
              "I built a canvas renderer behind a flag and measured both on the same scene:\n\n| Plants | DOM | Canvas |\n|---|---|---|\n| 200 | 60 fps | 60 fps |\n| 1000 | 41 fps | 60 fps |\n| 5000 | 9 fps | 38 fps |\n\nCanvas wins above ~1500 plants but loses text sharpness and accessibility. I'd keep DOM below that and switch automatically. I have not wired the switch yet; tell me if you want it.",
              { ageMin: 650, files: 4, model: "opus", effort: "max" }),
          ] },
          { name: "Plant shop", turns: [
            T("Add a plant shop screen with prices, a cart and a checkout stub.",
              null, { ageMin: 95, state: "failed", model: "sonnet",
                error: { code: "timeout", summary: "The job took too long", detail: "Claude Code was stopped after 30 minutes without finishing. The work done so far is left uncommitted in the working tree." } }),
          ] },
        ],
        commits: [
          C(60 * 24 * 7 + 5, "Baseline before Codenvi", 0, 0, 0, { kind: "baseline" }),
          C(690, "Render plants from one animation loop", 5, 88, 64),
          C(640, "Try a canvas renderer", 4, 190, 41),
          C(600, "Roll back to \"Render plants from one animation loop\"", 4, 41, 190, { kind: "rollback", revert: 1 }),
        ],
      },
      {
        name: "Dotfiles", script: "none", defaultSession: 0,
        git: { ahead: 0, behind: 0,
          changes: [],
          claudeMd: "# Dotfiles\n\nMy shell, editor and git configuration.\n\n- Scripts must be safe to run twice.\n- Test changes in a clean container before committing.\n" },
        sessions: [
          { name: "Shell setup", turns: [
            T("Make my shell setup work on a fresh machine with one command.",
              "Added `bootstrap.sh` that installs the packages, links the config files and is safe to run twice. Tested in a clean container.",
              { ageMin: 5000, files: 3, model: "haiku", effort: "low" }),
          ] },
          { name: "Editor config", turns: [
            T("Port my Vim config to Neovim with Lua, keep the same keybindings.",
              null, { ageMin: 400, state: "cancelled", model: "sonnet" }),
          ] },
        ],
        commits: [
          C(60 * 24 * 7 + 5, "Baseline before Codenvi", 0, 0, 0, { kind: "baseline" }),
          C(4990, "Add idempotent bootstrap script", 3, 74, 2),
        ],
      },
    ],
  },
  {
    name: "Work Laptop", os: "Windows 11 Pro 23H2", seenMin: 11,
    projects: [
      {
        name: "Checkout Web App", script: "idle", defaultSession: 0,
        git: { ahead: 4, behind: 0,
          changes: [["N", "api/webhooks/stripe.ts"],
            ["N", "api/webhooks/stripe.test.ts"],
            ["U", "api/orders/status.ts"],
            ["U", "api/orders/orders.test.ts"],
            ["U", "web/src/components/checkout/AddressStep.tsx"],
            ["U", "web/src/components/checkout/PaymentStep.tsx"],
            ["N", "web/src/emails/templates/paymentFailedReminder.html"],
            ["U", "web/src/i18n/locales/en/checkout.json"],
            ["U", "web/src/i18n/locales/de/checkout.json"],
            ["D", "web/src/components/checkout/LegacyCardForm.tsx"],
            ["U", ".env.example"]],
          claudeMd: "# Checkout Web App\n\nNext.js storefront with a Node API.\n\n## Commands\n- `npm run dev`\n- `npm test`\n- `npm run lint`\n\n## Rules\n- Never log card data or customer emails.\n- Money is always integer cents.\n" },
        sessions: [
          { name: "Stripe webhooks", turns: [
            T("Handle the Stripe `invoice.paid` and `invoice.payment_failed` webhooks and update the order status.",
              "Both webhooks are handled in `api/webhooks/stripe.ts`.\n\n- Signature verified with the signing secret\n- Idempotent: repeated events are ignored using the event id\n- `invoice.payment_failed` marks the order `past_due` and queues the reminder email\n\n```\n12 passing (stripe.test.ts)\n```",
              { ageMin: 4, state: "unseen", files: 5, model: "opus", effort: "xhigh" }),
          ] },
          { name: "Accessibility pass", turns: [
            T("Run an accessibility pass on the checkout form and fix what you find.",
              "Fixed 9 issues: missing labels, low-contrast error text, focus order on the address step and no `aria-live` region for validation errors. The form is now fully keyboard-operable.",
              { ageMin: 1900, files: 7 }),
          ] },
          { name: "Bundle size", turns: [
            T("Why is the main bundle 1.8 MB? Cut it down.",
              "Two culprits: a full date library and an unused chart package. Replaced the first with a 2 kB helper, lazy-loaded the second.\n\n**1.8 MB → 640 kB** (gzip 210 kB).",
              { ageMin: 2600, files: 4 }),
            T("Show me the biggest remaining dependencies.",
              "Top five by size after the change:\n\n1. `react-dom` – 130 kB\n2. `zod` – 52 kB\n3. `@stripe/stripe-js` – 41 kB (loaded only on the payment step)\n4. `i18next` – 38 kB\n5. `libphonenumber-js` – 34 kB (could use the `min` build: −26 kB)\n\nNothing else is above 20 kB.",
              { ageMin: 2590, files: 0, model: "haiku", effort: "low" }),
          ] },
        ],
        commits: [
          C(60 * 24 * 7 + 5, "Baseline before Codenvi", 0, 0, 0, { kind: "baseline" }),
          C(2570, "Trim the main bundle by 64%", 4, 31, 22),
          C(1880, "Fix checkout form accessibility issues", 7, 58, 33),
        ],
      },
      {
        name: "Mobile Client", script: "none", defaultSession: 1,
        git: { ahead: 0, behind: 1,
          changes: [["N", "android/app/src/main/java/app/codenvi/data/Outbox.kt"],
            ["N", "android/app/src/main/java/app/codenvi/data/OutboxDao.kt"],
            ["U", "android/app/src/main/java/app/codenvi/repo/Repository.kt"],
            ["U", "android/app/src/main/java/app/codenvi/ui/screens/ChatScreen.kt"],
            ["U", "android/app/src/main/java/app/codenvi/db/AppDatabase.kt"],
            ["U", "android/app/src/main/java/app/codenvi/CodenviApp.kt"],
            ["N", "android/app/src/test/java/app/codenvi/OutboxTest.kt"],
            ["D", "android/app/src/main/res/drawable/ic_clock_old.xml"]],
          claudeMd: "# Mobile Client\n\nKotlin + Jetpack Compose app.\n\n## Commands\n- `./gradlew testDebugUnitTest`\n- `./gradlew assembleDebug`\n\n## Rules\n- Min SDK 26.\n- Keep strings in `strings.xml`.\n" },
        sessions: [
          { name: "Offline mode", turns: [
            T("Let the app queue messages while offline and send them when the connection is back.",
              "Added an outbox stored in the local database. Messages show a clock icon while queued and are sent in order on reconnect, with retry and back-off.\n\n```kotlin\nsuspend fun flushOutbox() {\n    for (m in outbox.pending()) {\n        runCatching { api.send(m) }\n            .onSuccess { outbox.markSent(m.id) }\n            .onFailure { return }\n    }\n}\n```",
              { ageMin: 8, state: "unseen", files: 8, model: "opus" }),
          ] },
          { name: "Crash on launch", turns: [
            T("Crash on launch on Android 8. Here is the stack trace: NullPointerException in ThemeStore.load",
              "`ThemeStore.load` read a preference that does not exist on first launch. It now falls back to the default and I added a regression test.",
              { ageMin: 3200, files: 2, model: "haiku", effort: "medium" }),
            T("Can you also check the other stores for the same mistake?",
              "Checked all 6 stores. `LanguageStore` and `SortStore` had the same pattern; both now fall back to defaults. The other four were already safe. Added one parameterised test that loads every store from an empty preferences file.",
              { ageMin: 3190, files: 3, model: "haiku", effort: "medium" }),
          ] },
          { name: "Release notes", turns: [
            T("Write the release notes for 2.4 from the commit history since 2.3.",
              null, { ageMin: 15, state: "waiting", model: "sonnet" }),
          ] },
        ],
        commits: [
          C(60 * 24 * 7 + 5, "Baseline before Codenvi", 0, 0, 0, { kind: "baseline" }),
          C(3180, "Fix launch crash on first run", 5, 44, 6),
        ],
      },
    ],
  },
  {
    name: "Build Server", os: "Windows Server 2022", seenMin: 2,
    projects: [
      {
        name: "CI Pipelines", script: "none", defaultSession: 0,
        git: { ahead: 0, behind: 0,
          changes: [["U", "Dockerfile"],
            ["N", ".dockerignore"],
            ["U", ".github/workflows/ci.yml"],
            ["D", "Dockerfile.old"]],
          claudeMd: "# CI Pipelines\n\nBuild and release automation.\n\n- Pin every action to a version.\n- Keep jobs under 10 minutes.\n" },
        sessions: [
          { name: "Cache dependencies", turns: [
            T("Cache the package manager and build outputs in CI so builds are faster.",
              "Added dependency and build caching keyed on the lockfile hash. Median build time: **9m 40s → 3m 15s**.",
              { ageMin: 2100, files: 2 }),
          ] },
          { name: "Docker image size", turns: [
            T("Our API image is 1.4 GB. Slim it down.", null,
              { ageMin: 3, state: "running", model: "sonnet",
                progress: ["Reading Dockerfile", "Running: docker history api:latest", "Editing Dockerfile (multi-stage build)"] }),
          ] },
          { name: "Nightly security scan", turns: [
            T("Add a nightly job that scans dependencies and opens an issue if it finds a high severity problem.",
              null, { ageMin: 25, state: "queued", model: "sonnet" }),
          ] },
        ],
        commits: [
          C(60 * 24 * 7 + 5, "Baseline before Codenvi", 0, 0, 0, { kind: "baseline" }),
          C(2090, "Cache dependencies and build output in CI", 2, 26, 4),
        ],
      },
      {
        name: "Data Warehouse", script: "none", defaultSession: 0,
        git: { ahead: 1, behind: 0,
          changes: [["N", "migrations/0042_add_orders_paid_index.sql"],
            ["U", "reports/weekly_revenue.sql"]],
          claudeMd: "# Data Warehouse\n\nPostgreSQL schema, migrations and reports.\n\n- Migrations are append-only and must be reversible.\n- Always run `EXPLAIN ANALYZE` on report changes.\n" },
        sessions: [
          { name: "Slow report query", turns: [
            T("The weekly revenue report takes 4 minutes. Make it faster without changing the result.",
              "The query scanned the whole `orders` table. A partial index on `status = 'paid'` plus rewriting a correlated subquery as a join brought it to **11 seconds**. Output verified identical on last quarter's data.\n\n```sql\nCREATE INDEX orders_paid_idx ON orders (created_at) WHERE status = 'paid';\n```",
              { ageMin: 6, state: "unseen", files: 3, model: "opus" }),
          ] },
          { name: "Backfill", turns: [
            T("Write a safe backfill script for the new `currency` column, in batches.",
              "`backfill_currency.sql` updates 10 000 rows per batch, commits between batches and can be stopped and resumed. Dry-run mode prints what it would change.",
              { ageMin: 4200, files: 1 }),
          ] },
          { name: "Failed migration", turns: [
            T("Apply migration 0042 to the staging database and report the result.", null,
              { ageMin: 130, state: "failed", model: "sonnet",
                error: { code: "claude_failed", summary: "Claude Code stopped with an error", detail: "Exit code 1. The last message was: psql: error: connection to server at \"staging-db\" (10.0.4.12), port 5432 failed: timeout expired." } }),
          ] },
        ],
        commits: [
          C(60 * 24 * 7 + 5, "Baseline before Codenvi", 0, 0, 0, { kind: "baseline" }),
          C(4190, "Add resumable currency backfill", 1, 58, 0),
          C(5, "Speed up the weekly revenue report", 3, 47, 29),
        ],
      },
    ],
  },
];

// Phones paired with this server (Settings page). The last one is a revoked old phone.
const PHONES = [
  { name: "Pixel 9 Pro", model: "Pixel 9 Pro", os: "Android 15", pairedDays: 12, seenMin: 1, ip: "203.0.113.24" },
  { name: "Galaxy Tab S9", model: "SM-X710", os: "Android 14", pairedDays: 9, seenMin: 240, ip: "198.51.100.7" },
  { name: "Old Pixel 6", model: "Pixel 6", os: "Android 13", pairedDays: 60, seenMin: 60 * 24 * 20, ip: "203.0.113.90", revokedDays: 20 },
];

const APP_VERSION = "1.0.46", APP_BUILD = 46;

// ------------------------------------------------------------------------------------------------------ builders
function build(now) {
  const installs = [], devices = [], projects = [], sessions = [], messages = [], jobs = [], commits = [];
  const keys = [];
  let nInstall = 0, nDevice = 0, nProject = 0, nSession = 0, nMessage = 0, nJob = 0, nCommit = 0;
  const at = (minAgo) => new Date(now - Math.round(minAgo * 60_000));
  const WEEK = 60 * 24 * 7;

  for (const ph of PHONES) {
    installs.push({
      id: seedId("install", ++nInstall), kind: "android", name: ph.name, osVersion: ph.os, deviceModel: ph.model, ip: ph.ip,
      paired: at(60 * 24 * ph.pairedDays), lastSeen: at(ph.seenMin), revoked: ph.revokedDays ? at(60 * 24 * ph.revokedDays) : null,
    });
  }

  for (const dev of DEVICES) {
    const installId = seedId("install", ++nInstall);
    const deviceId = seedId("device", ++nDevice);
    const key = sha256(Buffer.from(`codenvi-demo-seed|${deviceId}`, "utf8"));
    keys.push({ deviceId, name: dev.name, key });
    installs.push({ id: installId, kind: "desktop", name: dev.name, osVersion: dev.os, deviceModel: null, ip: null, paired: at(60 * 24 * 14), lastSeen: at(dev.seenMin), revoked: null });
    devices.push({ id: deviceId, installId, name: dev.name, keyCheck: keyCheck(key), lastOnline: at(dev.seenMin), created: at(60 * 24 * 14) });

    dev.projects.forEach((proj, pIdx) => {
      const projectId = seedId("project", ++nProject);
      const sessionRows = [];
      let latest = WEEK;

      for (const sess of proj.sessions) {
        const sessionId = seedId("session", ++nSession);
        let seq = 0;
        let sessLatest = WEEK;
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
            status: "succeeded", errorCode: null, encError: null, queued: promptTime, delivered: null, started: null, finished: null };
          sessLatest = Math.min(sessLatest, turn.ageMin);
          const at2 = (ms) => new Date(promptTime.getTime() + ms);

          switch (turn.state) {
            case "queued":
              job.status = "queued";
              break;
            case "waiting":
              job.status = "waiting_login";
              job.delivered = at2(2_000);
              break;
            case "running":
              job.status = "running";
              job.delivered = at2(2_000);
              job.started = at2(5_000);
              turn.progress.forEach((line, i) => {
                const t = at2(15_000 + i * 15_000);
                const id = seedId("message", ++nMessage);
                seq += 1;
                messages.push({ id, sessionId, seq, role: "progress", jobId, created: t,
                  ciphertext: encrypt(key, deviceId, "progress", id, "d2p", t.getTime(), { line }) });
              });
              break;
            case "cancelled":
              job.status = "cancelled";
              job.delivered = at2(2_000);
              job.started = at2(5_000);
              job.finished = at2(48_000);
              break;
            case "failed":
              job.status = "failed";
              job.delivered = at2(2_000);
              job.started = at2(5_000);
              job.finished = at2(turn.error.code === "timeout" ? 30 * 60_000 : 21_000);
              job.errorCode = turn.error.code;
              job.encError = encrypt(key, deviceId, "job.error", jobId, "d2p", 0, { summary: turn.error.summary, detail: turn.error.detail });
              break;
            default: { // done | unseen
              const duration = 40_000 + ((nJob * 7919) % 160_000);
              job.delivered = at2(2_000);
              job.started = at2(5_000);
              job.finished = at2(5_000 + duration);
              const replyId = seedId("message", ++nMessage);
              seq += 1;
              messages.push({
                id: replyId, sessionId, seq, role: "assistant", jobId, created: job.finished,
                ciphertext: encrypt(key, deviceId, "message", replyId, "d2p", job.finished.getTime(), {
                  text: turn.reply, costUsd: Math.round((0.04 + (nJob % 9) * 0.031) * 1000) / 1000, durationMs: duration,
                  commitHash: null, filesChanged: turn.files, part: 1, parts: 1 }),
              });
            }
          }
          jobs.push(job);
        }
        latest = Math.min(latest, sessLatest);
        sessionRows.push({ id: sessionId, projectId, name: sess.name, created: at(WEEK), updated: at(sessLatest) });
      }

      // Commits of the project, oldest first. A rollback marks the `revert` task commits just before it as reverted by it.
      const made = [];
      for (const c of proj.commits) {
        const id = seedId("commit", ++nCommit);
        const gitHash = crypto.createHash("sha1").update(`demo-commit-${id}`).digest("hex");
        const row = {
          id, projectId, jobId: null, gitHash, kind: c.kind, created: at(c.ageMin), revertedBy: null,
          encMeta: encrypt(key, deviceId, "commit.meta", id, "meta", 0,
            { message: c.message, filesChanged: c.files, insertions: c.ins, deletions: c.del }),
        };
        if (c.kind === "rollback") {
          const tasks = made.filter((m) => m.kind === "task" && !m.revertedBy);
          for (const m of tasks.slice(-c.revert)) m.revertedBy = id;
        }
        made.push(row);
      }
      commits.push(...made);

      for (const s of sessionRows) {
        sessions.push({ ...s, encMeta: encrypt(key, deviceId, "session.meta", s.id, "meta", 0, { name: s.name }) });
      }
      const meta = { name: proj.name, devBranch: "codenvi-dev" };
      if (proj.script !== "none") { meta.hasScript = true; if (proj.script === "running") meta.scriptRunning = true; }
      projects.push({
        name: proj.name, git: proj.git, commits: made, script: proj.script,
        id: projectId, deviceId, defaultSessionId: sessionRows[proj.defaultSession ?? 0].id, sortOrder: pIdx,
        encMeta: encrypt(key, deviceId, "project.meta", projectId, "meta", 0, meta),
        created: at(WEEK + 10), updated: at(latest),
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
  const candidates = [path.join(path.dirname(envFile), "..", "package.json"), path.join(process.cwd(), "package.json"), __filename];
  let Client;
  for (const c of candidates) {
    try { ({ Client } = createRequire(c)("pg")); break; } catch { /* try the next */ }
  }
  if (!Client) throw new Error("Cannot load the 'pg' package. Run: NODE_PATH=/var/www/<name>/node_modules node demo-seed.js");
  return new Client({ connectionString: url });
}

async function insertAll(c, d) {
  // Start clean: remove demo rows from an earlier run (only rows whose id starts with the seed prefix).
  await c.query("DELETE FROM devices WHERE id::text LIKE $1", [`${SEED_PREFIX}%`]);
  await c.query("DELETE FROM installs WHERE id::text LIKE $1", [`${SEED_PREFIX}%`]);

  for (const i of d.installs) {
    await c.query(
      `INSERT INTO installs (id, kind, name, app_version, app_build, os_version, device_model, last_ip, paired_at, last_seen_at, revoked_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)`,
      [i.id, i.kind, i.name, APP_VERSION, APP_BUILD, i.osVersion, i.deviceModel, i.ip, i.paired, i.lastSeen, i.revoked]);
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
                         error_code, enc_error, queued_at, delivered_at, started_at, finished_at)
       VALUES ($1, $2, $3, $4, $5, 'prompt', $6, $7, $8, $9, $10, $11, $12, $13, $14)`,
      [j.id, j.deviceId, j.projectId, j.sessionId, j.promptMessageId, j.model, j.effort, j.status, j.errorCode, j.encError,
        j.queued, j.delivered, j.started, j.finished]);
  }
  for (const k of d.commits) {
    await c.query(
      `INSERT INTO commits (id, project_id, job_id, git_hash, kind, enc_meta, created_at) VALUES ($1, $2, $3, $4, $5, $6, $7)`,
      [k.id, k.projectId, k.jobId, k.gitHash, k.kind, k.encMeta, k.created]);
  }
  // A rollback commit marks the commits it reverted (set after all rows exist, because it points at another commit row).
  for (const k of d.commits.filter((x) => x.revertedBy)) {
    await c.query("UPDATE commits SET reverted_by = $1 WHERE id = $2", [k.revertedBy, k.id]);
  }
}

async function main() {
  const dryRun = process.argv.includes("--dry-run");
  const data = build(Date.now());
  const summary = `${data.devices.length} PCs, ${data.installs.length - data.devices.length} phones, ${data.projects.length} projects, ${data.sessions.length} sessions, ` +
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
    "while that PC is connected. To bring them online, with files on the Code changes page, Commit / Pull / Publish buttons and\n" +
    "a CLAUDE.md on the Memory tab, leave this running next to the server:  node demo-pc.js\n" +
    "Replies marked unseen appear as white dots on the phone.\n" +
    "Remove everything with: node demo-cleanup.js");
}

if (require.main === module) {
  main().catch((e) => { console.error(`Seed failed, nothing was changed: ${e.message}`); process.exit(1); });
}
module.exports = { base32, keyCompact, keyCheck, encKeyFor, encrypt, build, seedId, DEVICES, KEY_VERSION, SEED_PREFIX, sha256 };
