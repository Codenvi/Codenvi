#!/usr/bin/env node
"use strict";
/*
 * Codenvi demo PCs: brings the fake PCs created by demo-seed.js online and lets them answer the phone, so the pages that
 * read from a PC show data. Run it on the server after demo-seed.js and leave it running while you use the demo:
 *
 *   node demo-pc.js
 *
 * What it adds to the demo
 *   - The three demo PCs show as Online on the Devices page.
 *   - Code changes page: each project lists its uncommitted files (new, updated, deleted), including a very long path.
 *   - Chat header: Commit appears for projects with changes, Pull for projects behind origin/main, Publish for projects
 *     ahead of main. They work: Commit empties the Code changes list, Pull and Publish clear their counters, and the next
 *     status check hides the buttons.
 *   - Memory tab: a CLAUDE.md per project (editable; kept in memory while this runs).
 *   - Script and rollback commands answer "ok".
 * Jobs are not run: the demo's running, queued and waiting jobs stay exactly as seeded (a dispatch is ignored, never acked).
 *
 * HOW IT CONNECTS (nothing real is touched)
 *   It signs an access token for each demo PC's fake install (their ids start with 5eed0000-) with the server's JWT_SECRET,
 *   exactly as the server would, and connects to the server's WebSocket on this machine. It never reads or writes the
 *   database, and it can only act as those fake installs. State lives in memory: restart it to reset the demo; stop it
 *   (Ctrl+C) and the PCs go offline again. demo-cleanup.js removes the demo rows as before.
 *
 * CONFIG (all optional)
 *   ENV_FILE          path of the app's .env; default /var/www/codenvi/shared/.env (JWT_SECRET and PORT are read from it)
 *   JWT_SECRET, PORT  override the .env values
 *   DEMO_SERVER_URL   WebSocket base URL; default ws://127.0.0.1:<PORT>
 */
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { createRequire } = require("node:module");
const seed = require("./demo-seed.js");

function loadEnv(file) {
  const out = {};
  if (!fs.existsSync(file)) return out;
  for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
    const m = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/.exec(line);
    if (!m || line.trimStart().startsWith("#")) continue;
    out[m[1]] = m[2].replace(/^(['"])(.*)\1$/, "$2");
  }
  return out;
}

function need(name) {
  const envFile = process.env.ENV_FILE || "/var/www/codenvi/shared/.env";
  const candidates = [path.join(path.dirname(envFile), "..", "package.json"), path.join(process.cwd(), "package.json"), __filename];
  for (const c of candidates) {
    try { return createRequire(c)(name); } catch { /* try the next */ }
  }
  throw new Error(`Cannot load the '${name}' package. Run: NODE_PATH=/var/www/<name>/node_modules node demo-pc.js`);
}

const envFile = process.env.ENV_FILE || "/var/www/codenvi/shared/.env";
const env = { ...loadEnv(envFile), ...process.env };
if (!env.JWT_SECRET) { console.error(`JWT_SECRET not found. Set ENV_FILE=/var/www/<name>/shared/.env or JWT_SECRET.`); process.exit(1); }
const jwt = need("jsonwebtoken");
const WebSocket = need("ws");
const BASE_URL = env.DEMO_SERVER_URL || `ws://127.0.0.1:${env.PORT || 5080}`;

// ----------------------------------------------------------------------------------------------- demo state
const data = seed.build(Date.now());
const keyOf = new Map(data.keys.map((k) => [k.deviceId, k.key]));
const sha1 = (s) => crypto.createHash("sha1").update(s).digest("hex");

/** One entry per demo project: what the "PC" currently has (files, counters, CLAUDE.md, script). */
const projectState = new Map();
for (const p of data.projects) {
  const last = p.commits[p.commits.length - 1];
  const lastMessage = last ? decodeMessage(keyOf.get(p.deviceId), p.deviceId, last) : "Baseline before Codenvi";
  projectState.set(p.id, {
    deviceId: p.deviceId, name: p.name, changes: p.git.changes.map(([k, f]) => ({ kind: k, path: f })),
    ahead: p.git.ahead, behind: p.git.behind, memory: p.git.claudeMd, scriptRunning: p.script === "running",
    lastHash: last ? last.gitHash : sha1(`demo-head-${p.id}`), lastMessage,
  });
}

/** The commit message sits inside the encrypted commit meta; read it back with the demo key. */
function decodeMessage(key, deviceId, commit) {
  const w = commit.encMeta;
  const d = crypto.createDecipheriv("aes-256-gcm", seed.encKeyFor(key, deviceId), w.subarray(3, 15), { authTagLength: 16 });
  d.setAAD(Buffer.from(`v1|${deviceId}|commit.meta|${commit.id}|meta|0`));
  d.setAuthTag(w.subarray(w.length - 16));
  return JSON.parse(Buffer.concat([d.update(w.subarray(15, w.length - 16)), d.final()]).toString("utf8")).message;
}

const changesText = (s) => s.changes.map((c) => `${c.kind}\t${c.path}`).join("\n") + (s.changes.length ? "\n" : "");

function statusOf(s) {
  return { branch: "codenvi-dev", dirty: s.changes.length > 0, lastCommitHash: s.lastHash, lastCommitMessage: s.lastMessage,
    runningJobId: null, branchOk: true, aheadOfMain: s.ahead, behindOrigin: s.behind };
}

const DIRTY = "The working tree has uncommitted changes - commit or discard them first.";

/** Answers one control command like the real PC would, changing the demo state where the real PC would change git. */
function handle(cmd, s) {
  switch (cmd.command) {
    case "project.status": return { ok: true, status: statusOf(s) };
    case "project.changes": return { ok: true, text: changesText(s) };
    case "project.memory.get": return { ok: true, text: s.memory };
    case "project.memory.set": s.memory = cmd.text ?? ""; return { ok: true };
    case "session.create": case "session.clearMemory": case "project.rollback": return { ok: true, text: sha1(`demo-${Date.now()}`) };
    case "project.commit": {
      if (!s.changes.length) return { ok: true, text: "Nothing to commit" };
      if (!cmd.text || !cmd.text.trim()) return { ok: false, error: "Enter a commit message." };
      const hash = sha1(`demo-commit-${Date.now()}-${cmd.text}`);
      s.changes = []; s.ahead += 1; s.lastHash = hash; s.lastMessage = cmd.text.trim().split(/\r?\n/)[0];
      return { ok: true, text: hash };
    }
    case "project.pull": {
      if (s.changes.length) return { ok: false, error: DIRTY };
      if (s.behind > 0) { s.behind = 0; s.lastHash = sha1(`demo-pull-${Date.now()}`); s.lastMessage = "Update dependencies and fix lint warnings"; }
      return { ok: true, text: "Update dependencies and fix lint warnings" };
    }
    case "project.publish": {
      if (s.changes.length) return { ok: false, error: DIRTY };
      s.ahead = 0;
      return { ok: true, text: s.lastHash };
    }
    case "project.script.run":
      if (s.scriptRunning) return { ok: false, error: "The script is already running." };
      s.scriptRunning = true; return { ok: true, text: "Script started" };
    case "project.script.stop": {
      const was = s.scriptRunning; s.scriptRunning = false;
      return { ok: true, text: was ? "Script stopped" : "No script was running" };
    }
    default: return { ok: false, error: `Unknown command '${cmd.command}'.` };
  }
}

// ----------------------------------------------------------------------------------------------- crypto + wire
function decryptControl(key, deviceId, requestId, createdAtMs, b64) {
  const w = Buffer.from(b64, "base64");
  if (w.length < 31 || w[0] !== 1) throw new Error("bad ciphertext");
  const d = crypto.createDecipheriv("aes-256-gcm", seed.encKeyFor(key, deviceId), w.subarray(3, 15), { authTagLength: 16 });
  d.setAAD(Buffer.from(`v1|${deviceId}|control|${requestId}|p2d|${createdAtMs}`, "utf8"));
  d.setAuthTag(w.subarray(w.length - 16));
  return JSON.parse(Buffer.concat([d.update(w.subarray(15, w.length - 16)), d.final()]).toString("utf8"));
}

const envelope = (type, payload) => JSON.stringify({ type, id: crypto.randomUUID(), ts: Date.now(), payload });

function connect(device) {
  const key = keyOf.get(device.id);
  const label = device.name;
  let closed = false;
  let ws;

  const open = () => {
    const token = jwt.sign({ iid: device.installId, kind: "desktop" }, env.JWT_SECRET, {
      algorithm: "HS256", issuer: "codenvi", audience: "codenvi-apps", expiresIn: "12h",
    });
    ws = new WebSocket(`${BASE_URL}/ws?token=${encodeURIComponent(token)}`);
    ws.on("open", () => {
      ws.send(envelope("hello", { role: "desktop", deviceId: device.id }));
      console.log(`${label}: online`);
    });
    ws.on("message", (raw) => {
      let env;
      try { env = JSON.parse(raw.toString("utf8")); } catch { return; }
      if (env.type === "error") console.log(`${label}: server says ${env.payload?.code} ${env.payload?.message ?? ""}`);
      if (env.type !== "control.request") return; // job.dispatch and the rest are ignored on purpose
      const p = env.payload;
      const s = projectState.get(p.projectId);
      let result;
      try {
        if (!s || s.deviceId !== device.id) throw new Error("unknown project");
        const cmd = decryptControl(key, device.id, p.requestId, p.clientCreatedAtMs, p.ciphertext);
        result = handle(cmd, s);
        console.log(`${label}: ${s.name}: ${cmd.command} -> ${result.ok ? "ok" : result.error}`);
      } catch (e) {
        result = { ok: false, error: "The demo PC could not read that request." };
        console.log(`${label}: bad control request (${e.message})`);
      }
      const now = Date.now();
      const iv = crypto.randomBytes(12);
      const c = crypto.createCipheriv("aes-256-gcm", seed.encKeyFor(key, device.id), iv, { authTagLength: 16 });
      c.setAAD(Buffer.from(`v1|${device.id}|control.response|${p.requestId}|d2p|${now}`, "utf8"));
      const body = Buffer.concat([c.update(Buffer.from(JSON.stringify(result), "utf8")), c.final()]);
      const wire = Buffer.concat([Buffer.from([1, seed.KEY_VERSION >> 8, seed.KEY_VERSION & 255]), iv, body, c.getAuthTag()]);
      ws.send(envelope("control.response", {
        requestId: p.requestId, deviceId: device.id, projectId: p.projectId, ciphertext: wire.toString("base64"), clientCreatedAtMs: now,
      }));
    });
    ws.on("close", (code) => {
      console.log(`${label}: disconnected (${code})`);
      if (!closed) setTimeout(open, 3000); // a new token each time, so the 12 h expiry never matters
    });
    ws.on("error", (e) => console.log(`${label}: ${e.message}`));
  };
  open();
  return () => { closed = true; try { ws.close(); } catch { /* already closed */ } };
}

const stops = data.devices.map(connect);
console.log(`Demo PCs connecting to ${BASE_URL} ... leave this running; Ctrl+C takes them offline.`);
process.on("SIGINT", () => { stops.forEach((f) => f()); setTimeout(() => process.exit(0), 300); });
process.on("SIGTERM", () => { stops.forEach((f) => f()); setTimeout(() => process.exit(0), 300); });
