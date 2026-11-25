import { randomBytes, randomUUID } from "node:crypto";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { sql } from "drizzle-orm";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import WebSocket from "ws";

// Runs only when tests/global-setup.ts could create a database (CODENVI_TEST_PG is set).
const enabled = process.env.CODENVI_TEST_DB_READY === "1";
const d = enabled ? describe : describe.skip;

let base = "";
let wsBase = "";
let server: http.Server;
let closeWs: () => void;
let dbModule: typeof import("../src/db/client");
let pairing: typeof import("../src/auth/pairing");

beforeAll(async () => {
  if (!enabled) return;
  const { createApp } = await import("../src/app");
  const { attachWebSocket } = await import("../src/ws");
  dbModule = await import("../src/db/client");
  pairing = await import("../src/auth/pairing");
  server = http.createServer(createApp());
  closeWs = attachWebSocket(server).close;
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  const port = (server.address() as AddressInfo).port;
  base = `http://127.0.0.1:${port}`;
  wsBase = `ws://127.0.0.1:${port}`;
});

afterAll(async () => {
  if (!enabled) return;
  closeWs();
  server.closeAllConnections();
  await new Promise((r) => server.close(r));
  await dbModule.pool.end();
});

interface Tokens { accessToken: string; refreshToken: string; installId: string }

async function call(method: string, path: string, token?: string | null, body?: unknown, kind = "desktop") {
  const res = await fetch(base + path, {
    method,
    headers: {
      "content-type": "application/json",
      "x-codenvi-client": `${kind}/1.0.0+1 (Test OS)`,
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  return { status: res.status, json: text ? JSON.parse(text) : undefined };
}

async function pair(kind: "desktop" | "android" = "desktop"): Promise<Tokens> {
  const { code } = await pairing.createCode("cli", null);
  const r = await call("POST", "/api/v1/pair", null, {
    code, installId: randomUUID(), kind, name: `Test ${kind}`, appVersion: "1.0.0", appBuild: 1, osVersion: "Test OS",
  }, kind);
  expect(r.status).toBe(200);
  return r.json;
}

const b64 = (n = 40) => randomBytes(n).toString("base64");

interface Rig { desktop: Tokens; phone: Tokens; deviceId: string; projectId: string; sessionId: string }

async function rig(): Promise<Rig> {
  const desktop = await pair("desktop");
  const phone = await pair("android");
  const dev = await call("POST", "/api/v1/devices", desktop.accessToken, { name: "PC", keyCheck: randomBytes(8).toString("base64") });
  expect(dev.status).toBe(200);
  const deviceId = dev.json.id as string;
  const projectId = randomUUID();
  const sessionId = randomUUID();
  expect((await call("PUT", `/api/v1/projects/${projectId}`, desktop.accessToken, { deviceId, encMeta: b64(), sortOrder: 0 })).status).toBe(204);
  expect((await call("PUT", `/api/v1/sessions/${sessionId}`, desktop.accessToken, { projectId, encMeta: b64() })).status).toBe(204);
  return { desktop, phone, deviceId, projectId, sessionId };
}

class Socket {
  private queue: Array<{ type: string; payload: any }> = [];
  private waiters: Array<{ type: string; resolve: (v: any) => void }> = [];
  closed: Promise<number>;
  private ws: WebSocket;

  constructor(token: string) {
    this.ws = new WebSocket(`${wsBase}/ws?token=${token}`);
    this.ws.on("message", (data) => {
      const env = JSON.parse(data.toString());
      const i = this.waiters.findIndex((w) => w.type === env.type);
      if (i >= 0) this.waiters.splice(i, 1)[0].resolve(env.payload);
      else this.queue.push(env);
    });
    this.closed = new Promise((r) => this.ws.on("close", (code) => r(code)));
  }

  open(): Promise<void> {
    return new Promise((res, rej) => { this.ws.on("open", () => res()); this.ws.on("error", rej); this.ws.on("unexpected-response", (_q, r) => rej(new Error(`HTTP ${r.statusCode}`))); });
  }

  send(type: string, payload: unknown): void { this.ws.send(JSON.stringify({ type, id: randomUUID(), ts: Date.now(), payload })); }

  next(type: string, timeoutMs = 5000): Promise<any> {
    const i = this.queue.findIndex((m) => m.type === type);
    if (i >= 0) return Promise.resolve(this.queue.splice(i, 1)[0].payload);
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error(`timeout waiting for ${type}`)), timeoutMs);
      this.waiters.push({ type, resolve: (v) => { clearTimeout(t); resolve(v); } });
    });
  }

  close(): void { this.ws.close(); }
}

async function connect(token: string): Promise<Socket> {
  const s = new Socket(token);
  await s.open();
  await s.next("welcome");
  return s;
}

d("REST API", () => {
  it("health is public", async () => {
    const r = await call("GET", "/health");
    expect(r.status).toBe(200);
    expect(r.json).toMatchObject({ status: "ok", database: true });
  });

  it("every endpoint except health, pair and refresh requires a valid token", async () => {
    const revoked = await pair();
    await call("DELETE", `/api/v1/installs/${revoked.installId}?confirmSelf=true`, revoked.accessToken);
    const id = randomUUID();
    const endpoints: Array<[string, string]> = [
      ["POST", "/pairing-codes"], ["GET", "/installs"], ["PATCH", `/installs/${id}`], ["DELETE", `/installs/${id}`],
      ["POST", "/devices"], ["GET", "/devices"], ["PATCH", `/devices/${id}`], ["POST", `/devices/${id}/key`], ["DELETE", `/devices/${id}`],
      ["GET", `/devices/${id}/projects`], ["GET", `/devices/${id}/jobs/active`],
      ["PUT", `/projects/${id}`], ["PATCH", `/projects/${id}`], ["DELETE", `/projects/${id}`],
      ["GET", `/projects/${id}/sessions`], ["GET", `/projects/${id}/commits`], ["PUT", `/projects/${id}/commits/${id}`], ["POST", `/projects/${id}/control`],
      ["PUT", `/sessions/${id}`], ["PATCH", `/sessions/${id}`], ["DELETE", `/sessions/${id}`],
      ["GET", `/sessions/${id}/messages`], ["POST", `/sessions/${id}/messages`], ["DELETE", `/sessions/${id}/messages`], ["GET", `/sessions/${id}/jobs`],
      ["POST", `/jobs/${id}/cancel`], ["GET", "/catalog/models"], ["GET", "/nope"],
    ];
    for (const [method, path] of endpoints) {
      for (const token of [null, revoked.accessToken, "garbage"]) {
        const r = await call(method, `/api/v1${path}`, token, method === "GET" || method === "DELETE" ? undefined : {});
        expect(r.status, `${method} ${path} with ${token ?? "no token"}`).toBe(401);
      }
    }
  });

  it("pairing code is single use", async () => {
    const { code } = await pairing.createCode("cli", "android");
    const req = { code, installId: randomUUID(), kind: "android", name: "Phone", appVersion: "1.0.0", appBuild: 1 };
    expect((await call("POST", "/api/v1/pair", null, req, "android")).status).toBe(200);
    expect((await call("POST", "/api/v1/pair", null, { ...req, installId: randomUUID() }, "android")).status).toBe(401);
  });

  it("pairing code for another kind is refused, and garbage codes are 401", async () => {
    const { code } = await pairing.createCode("cli", "desktop");
    const base = { installId: randomUUID(), name: "x", appVersion: "1.0.0", appBuild: 1 };
    expect((await call("POST", "/api/v1/pair", null, { ...base, code, kind: "android" }, "android")).status).toBe(401);
    expect((await call("POST", "/api/v1/pair", null, { ...base, code: "!!!", kind: "android" }, "android")).status).toBe(401);
  });

  it("a refresh whose response was lost can be retried, and the app stays connected", async () => {
    const t = await pair();
    const first = await call("POST", "/api/v1/token/refresh", null, { installId: t.installId, refreshToken: t.refreshToken });
    expect(first.status).toBe(200);
    // The phone never received `first` and sends the old token again.
    const retry = await call("POST", "/api/v1/token/refresh", null, { installId: t.installId, refreshToken: t.refreshToken });
    expect(retry.status).toBe(200);
    expect(retry.json.refreshToken).not.toBe(first.json.refreshToken);
    expect((await call("GET", "/api/v1/installs", retry.json.accessToken)).status).toBe(200);
    // The lost successor is no longer valid; the retried one keeps working.
    expect((await call("POST", "/api/v1/token/refresh", null, { installId: t.installId, refreshToken: first.json.refreshToken })).status).toBe(401);
    const next = await call("POST", "/api/v1/token/refresh", null, { installId: t.installId, refreshToken: retry.json.refreshToken });
    expect(next.status).toBe(200);
    expect((await call("GET", "/api/v1/installs", next.json.accessToken)).status).toBe(200);
  });

  it("reuse of a refresh token whose successor was already used revokes the install", async () => {
    const t = await pair();
    const first = await call("POST", "/api/v1/token/refresh", null, { installId: t.installId, refreshToken: t.refreshToken });
    expect(first.status).toBe(200);
    // The legitimate app uses its new token...
    const second = await call("POST", "/api/v1/token/refresh", null, { installId: t.installId, refreshToken: first.json.refreshToken });
    expect(second.status).toBe(200);
    // ...so someone presenting the old one again holds a copy: revoke.
    const reuse = await call("POST", "/api/v1/token/refresh", null, { installId: t.installId, refreshToken: t.refreshToken });
    expect(reuse.status).toBe(401);
    expect((await call("GET", "/api/v1/installs", second.json.accessToken)).status).toBe(401);
  });

  it("an old access token from a different install id header is rejected", async () => {
    const t = await pair();
    const res = await fetch(`${base}/api/v1/installs`, {
      headers: { authorization: `Bearer ${t.accessToken}`, "x-codenvi-install-id": randomUUID(), "x-codenvi-client": "desktop/1.0.0+1" },
    });
    expect(res.status).toBe(401);
  });


  it("prompt flow creates a message and a queued job; a phone cannot register projects", async () => {
    const r = await rig();
    const messageId = randomUUID();
    const send = await call("POST", `/api/v1/sessions/${r.sessionId}/messages`, r.phone.accessToken,
      { messageId, ciphertext: b64(), clientCreatedAtMs: Date.now(), model: "sonnet", effort: "high" }, "android");
    expect(send.status).toBe(200);
    expect(send.json).toMatchObject({ messageId, seq: 1 });

    const jobs = await call("GET", `/api/v1/sessions/${r.sessionId}/jobs`, r.phone.accessToken, undefined, "android");
    expect(jobs.json).toHaveLength(1);
    expect(jobs.json[0]).toMatchObject({ status: "queued", model: "sonnet", effort: "high", kind: "prompt" });
    expect(jobs.json[0]).not.toHaveProperty("errorCode"); // null fields are omitted

    const dup = await call("POST", `/api/v1/sessions/${r.sessionId}/messages`, r.phone.accessToken,
      { messageId, ciphertext: b64(), clientCreatedAtMs: Date.now() }, "android");
    expect(dup.status).toBe(409);

    const unknownModel = await call("POST", `/api/v1/sessions/${r.sessionId}/messages`, r.phone.accessToken,
      { messageId: randomUUID(), ciphertext: b64(), clientCreatedAtMs: Date.now(), model: "gpt" }, "android");
    expect(unknownModel.status).toBe(400);

    const phoneTry = await call("PUT", `/api/v1/projects/${randomUUID()}`, r.phone.accessToken,
      { deviceId: r.deviceId, encMeta: b64(), sortOrder: 0 }, "android");
    expect(phoneTry.status).toBe(403);

    const msgs = await call("GET", `/api/v1/sessions/${r.sessionId}/messages?afterSeq=0&limit=10`, r.phone.accessToken, undefined, "android");
    expect(msgs.json).toHaveLength(1);
    expect(msgs.json[0]).toMatchObject({ id: messageId, seq: 1, role: "user" });
  });

  it("rejects oversized ciphertexts with 413 and too-short ones with 400", async () => {
    const r = await rig();
    const big = Buffer.alloc(1024 * 1024 + 1, 1).toString("base64");
    const tooBig = await call("POST", `/api/v1/sessions/${r.sessionId}/messages`, r.phone.accessToken,
      { messageId: randomUUID(), ciphertext: big, clientCreatedAtMs: Date.now() }, "android");
    expect(tooBig.status).toBe(413);
    const tiny = await call("POST", `/api/v1/sessions/${r.sessionId}/messages`, r.phone.accessToken,
      { messageId: randomUUID(), ciphertext: b64(10), clientCreatedAtMs: Date.now() }, "android");
    expect(tiny.status).toBe(400);
  });

  it("key rotation must increase the version and cancels pending jobs", async () => {
    const r = await rig();
    await call("POST", `/api/v1/sessions/${r.sessionId}/messages`, r.phone.accessToken,
      { messageId: randomUUID(), ciphertext: b64(), clientCreatedAtMs: Date.now() }, "android");
    const body = { keyVersion: 2, keyCheck: randomBytes(8).toString("base64") };
    expect((await call("POST", `/api/v1/devices/${r.deviceId}/key`, r.phone.accessToken, body, "android")).status).toBe(403);
    expect((await call("POST", `/api/v1/devices/${r.deviceId}/key`, r.desktop.accessToken, body)).status).toBe(204);
    expect((await call("POST", `/api/v1/devices/${r.deviceId}/key`, r.desktop.accessToken, body)).status).toBe(409);
    const jobs = await call("GET", `/api/v1/sessions/${r.sessionId}/jobs`, r.phone.accessToken, undefined, "android");
    expect(jobs.json[0]).toMatchObject({ status: "cancelled", errorCode: "key_rotated" });
  });

  it("commits, session delete and project delete", async () => {
    const r = await rig();
    const commitId = randomUUID();
    const put = { gitHash: "a".repeat(40), kind: "task", encMeta: b64() };
    expect((await call("PUT", `/api/v1/projects/${r.projectId}/commits/${commitId}`, r.desktop.accessToken, put)).status).toBe(204);
    expect((await call("PUT", `/api/v1/projects/${r.projectId}/commits/${randomUUID()}`, r.desktop.accessToken, { ...put, gitHash: "short" })).status).toBe(400);
    const list = await call("GET", `/api/v1/projects/${r.projectId}/commits`, r.phone.accessToken, undefined, "android");
    expect(list.json[0]).toMatchObject({ id: commitId, gitHash: "a".repeat(40), kind: "task" });

    expect((await call("DELETE", `/api/v1/sessions/${r.sessionId}`, r.phone.accessToken, undefined, "android")).status).toBe(204);
    expect((await call("GET", `/api/v1/projects/${r.projectId}/sessions`, r.phone.accessToken, undefined, "android")).json).toHaveLength(0);
    expect((await call("DELETE", `/api/v1/projects/${r.projectId}`, r.phone.accessToken, undefined, "android")).status).toBe(403);
    expect((await call("DELETE", `/api/v1/projects/${r.projectId}`, r.desktop.accessToken)).status).toBe(204);
  });

  it("model catalog is seeded", async () => {
    const t = await pair("android");
    const r = await call("GET", "/api/v1/catalog/models", t.accessToken, undefined, "android");
    expect(r.json.map((m: any) => m.alias)).toEqual(["default", "sonnet", "opus", "haiku", "fable"]);
    expect(r.json.map((m: any) => m.label)).toEqual(["Default (PC setting)", "Sonnet 5.5", "Opus 5.5", "Haiku 5.5", "Fable 5.2 (optional)"]);
  });

  it("pairing codes and installs", async () => {
    const t = await pair("desktop");
    const code = await call("POST", "/api/v1/pairing-codes", t.accessToken, { intendedKind: "android" });
    expect(code.status).toBe(200);
    expect(code.json.code).toHaveLength(26);
    expect(code.json.host).toBe("codenvi.test");
    const phone = await pair("android");
    expect((await call("POST", "/api/v1/pairing-codes", phone.accessToken, {}, "android")).status).toBe(403);
    const installs = await call("GET", "/api/v1/installs", t.accessToken);
    expect(installs.json.find((i: any) => i.isSelf).id).toBe(t.installId);
    expect((await call("DELETE", `/api/v1/installs/${t.installId}`, t.accessToken)).status).toBe(409); // needs confirmSelf
  });
});

d("linking apps with the server password", () => {
  const PASSWORD = "Test-Password_for.Codenvi-42"; // any characters, not only hex
  const body = (kind: string, code = PASSWORD) => ({ code, installId: randomUUID(), kind, name: `App ${kind}`, appVersion: "1.0.0", appBuild: 1 });

  it("links any number of PCs and phones, each with its own credential, and the password never expires", async () => {
    const apps = [] as Array<{ kind: string; tokens: Tokens }>;
    for (const kind of ["desktop", "desktop", "android", "desktop", "android"]) {
      const r = await call("POST", "/api/v1/pair", null, body(kind), kind);
      expect(r.status, `${kind} with the password`).toBe(200);
      apps.push({ kind, tokens: r.json });
    }
    expect(new Set(apps.map((a) => a.tokens.installId)).size).toBe(5);
    for (const a of apps) expect((await call("GET", "/api/v1/installs", a.tokens.accessToken, undefined, a.kind)).status).toBe(200);
    // Surrounding spaces from copy and paste are ignored.
    expect((await call("POST", "/api/v1/pair", null, body("desktop", `  ${PASSWORD}
`))).status).toBe(200);
  });

  it("each linked app can be revoked on its own without affecting the others", async () => {
    const a = (await call("POST", "/api/v1/pair", null, body("desktop"))).json as Tokens;
    const b = (await call("POST", "/api/v1/pair", null, body("desktop"))).json as Tokens;
    expect((await call("DELETE", `/api/v1/installs/${a.installId}`, b.accessToken)).status).toBe(204);
    expect((await call("GET", "/api/v1/installs", a.accessToken)).status).toBe(401);
    expect((await call("GET", "/api/v1/installs", b.accessToken)).status).toBe(200);
  });

  it("a wrong password is refused, including near misses and a different letter case", async () => {
    for (const wrong of ["Test-Password_for.Codenvi-43", PASSWORD.slice(0, -1), PASSWORD + "x", PASSWORD.toLowerCase(), "wrong"]) {
      expect((await call("POST", "/api/v1/pair", null, body("desktop", wrong))).status, wrong).toBe(401);
    }
  });

  it("the same install id cannot be linked twice", async () => {
    const req = body("desktop");
    expect((await call("POST", "/api/v1/pair", null, req)).status).toBe(200);
    expect((await call("POST", "/api/v1/pair", null, req)).status).toBe(400);
  });
});

d("WebSocket", () => {
  it("rejects a missing or bad token", async () => {
    await expect(new Socket("bad").open()).rejects.toThrow(/401/);
  });

  it("delivers a prompt to the PC, relays status, progress and result to the phone", async () => {
    const r = await rig();
    const desktop = await connect(r.desktop.accessToken);
    const phone = await connect(r.phone.accessToken);

    desktop.send("hello", { role: "desktop", deviceId: r.deviceId });
    const presence = await phone.next("device.presence");
    expect(presence).toEqual({ deviceId: r.deviceId, online: true });
    phone.send("hello", { role: "android" });

    const messageId = randomUUID();
    const prompt = b64();
    const send = await call("POST", `/api/v1/sessions/${r.sessionId}/messages`, r.phone.accessToken,
      { messageId, ciphertext: prompt, clientCreatedAtMs: 1764000000000, model: "opus" }, "android");
    const jobId = send.json.jobId as string;

    const dispatch = await desktop.next("job.dispatch");
    expect(dispatch.job).toMatchObject({ id: jobId, deviceId: r.deviceId, projectId: r.projectId, sessionId: r.sessionId, kind: "prompt", model: "opus" });
    expect(dispatch.prompt).toEqual({ messageId, ciphertext: prompt, clientCreatedAtMs: 1764000000000 });
    expect(dispatch.recentMessageIds).toEqual([messageId]);

    desktop.send("job.ack", { jobId });
    expect(await phone.next("job.status")).toMatchObject({ jobId, status: "delivered", sessionId: r.sessionId });
    desktop.send("job.status", { jobId, status: "running" });
    expect(await phone.next("job.status")).toMatchObject({ jobId, status: "running" });

    const progressId = randomUUID();
    desktop.send("job.progress", { jobId, sessionId: r.sessionId, messageId: progressId, ciphertext: b64(), clientCreatedAtMs: Date.now() });
    expect(await phone.next("job.progress")).toMatchObject({ jobId, messageId: progressId });

    const resultId = randomUUID();
    const result = b64(100);
    desktop.send("job.result", { jobId, sessionId: r.sessionId, messageId: resultId, ciphertext: result, clientCreatedAtMs: Date.now(), commitHash: "b".repeat(40), final: true });
    expect(await phone.next("job.result")).toMatchObject({ jobId, messageId: resultId, ciphertext: result, commitHash: "b".repeat(40) });
    desktop.send("job.status", { jobId, status: "succeeded" });
    const done = await phone.next("job.status");
    expect(done).toMatchObject({ jobId, status: "succeeded" });
    expect(done.finishedAtMs).toBeGreaterThan(0);

    const msgs = await call("GET", `/api/v1/sessions/${r.sessionId}/messages`, r.phone.accessToken, undefined, "android");
    expect(msgs.json.map((m: any) => [m.role, m.seq])).toEqual([["user", 1], ["progress", 2], ["assistant", 3]]);

    // A finished job cannot be revived by a late status message.
    desktop.send("job.status", { jobId, status: "running" });
    await new Promise((res) => setTimeout(res, 200));
    const jobs = await call("GET", `/api/v1/sessions/${r.sessionId}/jobs`, r.phone.accessToken, undefined, "android");
    expect(jobs.json[0].status).toBe("succeeded");

    desktop.close();
    expect(await phone.next("device.presence")).toEqual({ deviceId: r.deviceId, online: false });
    phone.close();
  });

  it("re-sends queued jobs when the PC connects after the prompt was sent", async () => {
    const r = await rig();
    const send = await call("POST", `/api/v1/sessions/${r.sessionId}/messages`, r.phone.accessToken,
      { messageId: randomUUID(), ciphertext: b64(), clientCreatedAtMs: Date.now() }, "android");
    const desktop = await connect(r.desktop.accessToken);
    desktop.send("hello", { role: "desktop", deviceId: r.deviceId });
    expect((await desktop.next("job.dispatch")).job.id).toBe(send.json.jobId);
    desktop.close();
  });

  it("a PC cannot say hello for a device it does not own", async () => {
    const r = await rig();
    const other = await pair("desktop");
    const s = await connect(other.accessToken);
    s.send("hello", { role: "desktop", deviceId: r.deviceId });
    expect((await s.next("error")).code).toBe("unknown_device");
    s.close();
  });

  it("cancels a queued job and tells the PC", async () => {
    const r = await rig();
    const desktop = await connect(r.desktop.accessToken);
    const phone = await connect(r.phone.accessToken);
    desktop.send("hello", { role: "desktop", deviceId: r.deviceId });
    await phone.next("device.presence");
    const send = await call("POST", `/api/v1/sessions/${r.sessionId}/messages`, r.phone.accessToken,
      { messageId: randomUUID(), ciphertext: b64(), clientCreatedAtMs: Date.now() }, "android");
    const jobId = send.json.jobId as string;
    await desktop.next("job.dispatch");
    desktop.send("job.status", { jobId, status: "running" });
    await phone.next("job.status");

    expect((await call("POST", `/api/v1/jobs/${jobId}/cancel`, r.phone.accessToken, undefined, "android")).status).toBe(202);
    expect(await desktop.next("job.cancel")).toEqual({ jobId });
    desktop.send("job.status", { jobId, status: "cancelled" });
    expect(await phone.next("job.status")).toMatchObject({ jobId, status: "cancelled" });
    expect((await call("POST", `/api/v1/jobs/${jobId}/cancel`, r.phone.accessToken, undefined, "android")).status).toBe(409);
    desktop.close(); phone.close();
  });

  it("routes control requests to the PC and the response back to the asking phone only", async () => {
    const r = await rig();
    const desktop = await connect(r.desktop.accessToken);
    const phone = await connect(r.phone.accessToken);
    const other = await connect((await pair("android")).accessToken);
    const requestId = randomUUID();

    expect((await call("POST", `/api/v1/projects/${r.projectId}/control`, r.phone.accessToken,
      { requestId, ciphertext: b64(), clientCreatedAtMs: Date.now() }, "android")).status).toBe(409); // PC offline

    desktop.send("hello", { role: "desktop", deviceId: r.deviceId });
    await phone.next("device.presence");
    const ciphertext = b64();
    expect((await call("POST", `/api/v1/projects/${r.projectId}/control`, r.phone.accessToken,
      { requestId, ciphertext, clientCreatedAtMs: 5 }, "android")).status).toBe(202);
    expect(await desktop.next("control.request")).toEqual({ requestId, deviceId: r.deviceId, projectId: r.projectId, ciphertext, clientCreatedAtMs: 5 });

    const reply = b64();
    desktop.send("control.response", { requestId, projectId: r.projectId, ciphertext: reply, clientCreatedAtMs: 6 });
    expect(await phone.next("control.response")).toMatchObject({ requestId, deviceId: r.deviceId, ciphertext: reply });
    await expect(other.next("control.response", 300)).rejects.toThrow(/timeout/);
    desktop.close(); phone.close(); other.close();
  });

  it("sync.changed reaches the other apps but not the one that made the change", async () => {
    const r = await rig();
    const desktop = await connect(r.desktop.accessToken);
    const phone = await connect(r.phone.accessToken);
    const newSession = randomUUID();
    await call("PUT", `/api/v1/sessions/${newSession}`, r.desktop.accessToken, { projectId: r.projectId, encMeta: b64() });
    expect(await phone.next("sync.changed")).toEqual({ entity: "session", id: newSession, parentId: r.projectId });
    await expect(desktop.next("sync.changed", 300)).rejects.toThrow(/timeout/);
    desktop.close(); phone.close();
  });

  it("revoking an install closes its connection; ping gets pong", async () => {
    const t = await pair("android");
    const s = await connect(t.accessToken);
    s.send("ping", {});
    await s.next("pong");
    expect((await call("DELETE", `/api/v1/installs/${t.installId}?confirmSelf=true`, t.accessToken, undefined, "android")).status).toBe(204);
    await s.closed;
  });
});
