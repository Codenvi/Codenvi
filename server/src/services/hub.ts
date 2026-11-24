import type { WebSocket } from "ws";
import { createEnvelope, MessageTypes } from "../lib/envelope";
import { log } from "../lib/log";

/** A connected app. One per install (a reconnect replaces the old one). */
export class ClientConnection {
  deviceId: string | null = null;
  helloReceived = false;
  isAlive = true;

  constructor(
    readonly installId: string,
    readonly kind: string,
    readonly socket: WebSocket,
    readonly tokenExpiresAtMs: number,
  ) {}

  /** `message` is an already-serialised envelope (see createEnvelope). */
  send(message: string): void {
    if (this.socket.readyState === this.socket.OPEN) {
      this.socket.send(message, (err) => { if (err) log.debug("ws send failed", err); });
    }
  }

  close(code: number, reason: string): void {
    try {
      if (this.socket.readyState === this.socket.OPEN) this.socket.close(code, reason);
    } catch { /* ignore */ }
  }
}

/**
 * In-memory registry of live connections (docs/design.md §7.4 "Server sizing"). The framework runs this app as a
 * single PM2 process, which is what makes an in-memory hub correct. Several instances would need Postgres
 * LISTEN/NOTIFY.
 */
class ConnectionHub {
  private readonly byInstall = new Map<string, ClientConnection>();

  all(): ClientConnection[] { return [...this.byInstall.values()]; }

  register(c: ClientConnection): ClientConnection | undefined {
    const previous = this.byInstall.get(c.installId);
    this.byInstall.set(c.installId, c);
    return previous;
  }

  unregister(c: ClientConnection): void {
    if (this.byInstall.get(c.installId) === c) this.byInstall.delete(c.installId);
  }

  forInstall(installId: string): ClientConnection | undefined { return this.byInstall.get(installId); }

  forDevice(deviceId: string): ClientConnection | undefined {
    for (const c of this.byInstall.values()) if (c.kind === "desktop" && c.deviceId === deviceId && c.helloReceived) return c;
    return undefined;
  }

  isDeviceOnline(deviceId: string): boolean { return this.forDevice(deviceId) !== undefined; }

  sendToDevice(deviceId: string, message: string): void { this.forDevice(deviceId)?.send(message); }
  sendToInstall(installId: string, message: string): void { this.forInstall(installId)?.send(message); }

  broadcastToAndroids(message: string): void {
    for (const c of this.byInstall.values()) if (c.kind === "android") c.send(message);
  }

  broadcast(message: string, exceptInstall?: string | null): void {
    for (const c of this.byInstall.values()) if (c.installId !== exceptInstall) c.send(message);
  }

  syncChanged(entity: string, id: string, parentId?: string | null, exceptInstall?: string | null): void {
    this.broadcast(createEnvelope(MessageTypes.SyncChanged, { entity, id, parentId: parentId ?? null }), exceptInstall);
  }

  disconnect(installId: string, reason: string): void {
    const c = this.byInstall.get(installId);
    if (!c) return;
    this.byInstall.delete(installId);
    log.info(`Disconnecting install ${installId}: ${reason}`);
    c.close(1008, reason);
    setTimeout(() => c.socket.terminate(), 1000).unref();
  }
}

export const hub = new ConnectionHub();
