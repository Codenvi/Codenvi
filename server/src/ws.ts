import type { IncomingMessage, Server } from "node:http";
import type { Duplex } from "node:stream";
import { sql } from "drizzle-orm";
import { WebSocketServer, type RawData } from "ws";
import { z } from "zod";
import { isActive } from "./auth/guard";
import { verifyAccessToken } from "./auth/tokens";
import { db, exec, exists } from "./db/client";
import { createEnvelope, envelopeSchema, MessageTypes } from "./lib/envelope";
import { b64Schema, uuidSchema } from "./lib/http";
import { log } from "./lib/log";
import { completeControl } from "./services/control";
import { ClientConnection, hub } from "./services/hub";
import * as jobs from "./services/jobs";

/**
 * wss://host/ws?token=ACCESS (docs/design.md §7.4). The token is validated on connect, and expiry/revocation is
 * re-checked on every received message.
 */
const MAX_MESSAGE_BYTES = 2 * 1024 * 1024;
const TOKEN_EXPIRED = 4001;
const REVOKED = 4003;
const PING_INTERVAL_MS = 25_000;

const helloSchema = z.object({ role: z.string(), deviceId: uuidSchema.nullish() });
const ackSchema = z.object({ jobId: uuidSchema });
const statusSchema = z.object({
  jobId: uuidSchema, status: z.string(), errorCode: z.string().nullish(), encError: b64Schema.nullish(),
});
const progressSchema = z.object({
  jobId: uuidSchema, sessionId: uuidSchema, messageId: uuidSchema, ciphertext: b64Schema, clientCreatedAtMs: z.number().int(),
});
const resultSchema = progressSchema.extend({ commitHash: z.string().nullish(), final: z.boolean().optional() });
const controlResponseSchema = z.object({
  requestId: uuidSchema, deviceId: uuidSchema.nullish(), projectId: uuidSchema, ciphertext: b64Schema, clientCreatedAtMs: z.number().int(),
});

export function attachWebSocket(server: Server): { close: () => void } {
  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_MESSAGE_BYTES });

  server.on("upgrade", (req: IncomingMessage, socket: Duplex, head: Buffer) => {
    handleUpgrade(wss, req, socket, head).catch((e) => {
      log.error("WebSocket upgrade failed", e);
      reject(socket, 500);
    });
  });

  // Keep-alive: protocol pings every 25 s; drop connections that stop answering.
  const timer = setInterval(() => {
    for (const c of hub.all()) {
      if (!c.isAlive) { c.socket.terminate(); continue; }
      c.isAlive = false;
      try { c.socket.ping(); } catch { /* closed */ }
    }
  }, PING_INTERVAL_MS);
  timer.unref();

  return {
    close: () => {
      clearInterval(timer);
      for (const c of hub.all()) c.socket.terminate();
      wss.close();
    },
  };
}

function reject(socket: Duplex, status: number): void {
  socket.write(`HTTP/1.1 ${status} ${status === 401 ? "Unauthorized" : status === 404 ? "Not Found" : "Error"}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
  socket.destroy();
}

async function handleUpgrade(wss: WebSocketServer, req: IncomingMessage, socket: Duplex, head: Buffer): Promise<void> {
  const url = new URL(req.url ?? "/", "http://localhost");
  if (url.pathname !== "/ws") return reject(socket, 404);

  const token = url.searchParams.get("token");
  const claims = token ? verifyAccessToken(token) : null;
  if (!claims || !(await isActive(claims.iid))) return reject(socket, 401);

  wss.handleUpgrade(req, socket, head, (ws) => {
    const connection = new ClientConnection(claims.iid, claims.kind, ws, claims.exp * 1000);
    const previous = hub.register(connection);
    previous?.close(1000, "replaced by new connection");
    log.info(`WS connected: ${connection.kind} ${connection.installId}`);

    ws.on("pong", () => { connection.isAlive = true; });
    connection.send(createEnvelope(MessageTypes.Welcome, {
      installId: connection.installId, serverVersion: process.env.npm_package_version ?? "1.0.0", serverTimeMs: Date.now(),
    }));

    // Messages from one connection are handled one after another, like the receive loop they replace.
    let chain: Promise<void> = Promise.resolve();
    ws.on("message", (data: RawData, isBinary: boolean) => {
      connection.isAlive = true;
      chain = chain.then(() => onMessage(connection, data, isBinary)).catch((e) => log.error("WS handler failed", e));
    });
    ws.on("error", (e) => log.debug("WS error", e));
    ws.on("close", () => {
      void onClose(connection).catch((e) => log.error("WS close handler failed", e));
    });
  });
}

async function onClose(c: ClientConnection): Promise<void> {
  hub.unregister(c);
  if (c.kind === "desktop" && c.deviceId && !hub.isDeviceOnline(c.deviceId)) {
    await markDeviceOnline(c.deviceId);
    hub.broadcastToAndroids(createEnvelope(MessageTypes.DevicePresence, { deviceId: c.deviceId, online: false }));
  }
  log.info(`WS disconnected: ${c.kind} ${c.installId}`);
}

const markDeviceOnline = (deviceId: string) =>
  exec(db, sql`UPDATE devices SET last_online_at = now() WHERE id = ${deviceId}::uuid`);

async function onMessage(c: ClientConnection, data: RawData, isBinary: boolean): Promise<void> {
  if (isBinary) return;
  if (Date.now() >= c.tokenExpiresAtMs) return c.close(TOKEN_EXPIRED, "token expired");
  if (!(await isActive(c.installId))) return c.close(REVOKED, "install revoked");

  let env: z.infer<typeof envelopeSchema>;
  try {
    env = envelopeSchema.parse(JSON.parse(data.toString("utf8")));
  } catch {
    return; // not an envelope: ignore
  }

  try {
    await dispatch(c, env.type, env.payload);
  } catch (e) {
    if (e instanceof z.ZodError) {
      log.warn(`Bad ${env.type} from ${c.installId}`);
      c.send(createEnvelope(MessageTypes.Error, { code: "bad_message", message: env.type }));
      return;
    }
    throw e;
  }
}

async function dispatch(c: ClientConnection, type: string, payload: unknown): Promise<void> {
  switch (type) {
    case MessageTypes.Ping:
      c.send(createEnvelope(MessageTypes.Pong, {}));
      return;
    case MessageTypes.Pong:
      return;
    case MessageTypes.Hello: {
      const hello = helloSchema.parse(payload);
      if (c.kind === "desktop" && hello.deviceId) {
        const owns = await exists(db, sql`
          SELECT 1 FROM devices WHERE id = ${hello.deviceId}::uuid AND install_id = ${c.installId}::uuid AND deleted_at IS NULL`);
        if (!owns) {
          c.send(createEnvelope(MessageTypes.Error, { code: "unknown_device", message: "Device is not registered to this install." }));
          return;
        }
        c.deviceId = hello.deviceId;
        c.helloReceived = true;
        await markDeviceOnline(hello.deviceId);
        hub.broadcastToAndroids(createEnvelope(MessageTypes.DevicePresence, { deviceId: hello.deviceId, online: true }));
        await jobs.resendPending(hello.deviceId);
      } else {
        c.helloReceived = true;
      }
      return;
    }
  }

  // Everything below is desktop → server and needs a registered device.
  if (c.kind !== "desktop" || !c.deviceId) {
    log.debug(`Ignoring ${type} from ${c.kind}`);
    return;
  }
  const device = c.deviceId;

  switch (type) {
    case MessageTypes.JobAck:
      await jobs.ack(device, ackSchema.parse(payload).jobId);
      break;
    case MessageTypes.JobStatus:
      await jobs.updateStatus(device, statusSchema.parse(payload));
      break;
    case MessageTypes.JobProgress:
      await jobs.storeProgress(device, progressSchema.parse(payload));
      break;
    case MessageTypes.JobResult:
      await jobs.storeResult(device, resultSchema.parse(payload));
      break;
    case MessageTypes.ControlResponse: {
      const p = controlResponseSchema.parse(payload);
      const target = completeControl(p.requestId, device);
      if (target) hub.sendToInstall(target, createEnvelope(MessageTypes.ControlResponse, { ...p, deviceId: device }));
      break;
    }
    default:
      log.debug(`Unknown message type ${type}`);
  }
}
