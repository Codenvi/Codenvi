import { randomUUID } from "node:crypto";
import { z } from "zod";
import { wire } from "./http";

/** WebSocket envelope: { "type": "...", "id": "uuid", "ts": 1764000000000, "payload": { } } */
export interface Envelope<T = unknown> { type: string; id: string; ts: number; payload: T }

export const MessageTypes = {
  Hello: "hello", Welcome: "welcome", JobDispatch: "job.dispatch", JobAck: "job.ack", JobStatus: "job.status",
  JobProgress: "job.progress", JobResult: "job.result", JobCancel: "job.cancel", ControlRequest: "control.request",
  ControlResponse: "control.response", DevicePresence: "device.presence", SyncChanged: "sync.changed",
  Ping: "ping", Pong: "pong", Error: "error",
} as const;

export const JobStatuses = {
  Queued: "queued", Delivered: "delivered", Running: "running", Succeeded: "succeeded", Failed: "failed",
  Cancelled: "cancelled", RejectedStale: "rejected_stale", WaitingLogin: "waiting_login",
} as const;
export const ALL_JOB_STATUSES: ReadonlySet<string> = new Set(Object.values(JobStatuses));
export const FINAL_JOB_STATUSES: ReadonlySet<string> = new Set(["succeeded", "failed", "cancelled", "rejected_stale"]);
export const isFinal = (status: string): boolean => FINAL_JOB_STATUSES.has(status);

export const SyncEntities = {
  Device: "device", Project: "project", Session: "session", Messages: "messages", Commits: "commits", Installs: "installs",
} as const;

export function createEnvelope<T>(type: string, payload: T): string {
  return wire({ type, id: randomUUID(), ts: Date.now(), payload } satisfies Envelope<T>);
}

export const envelopeSchema = z.object({ type: z.string(), id: z.string().optional(), ts: z.number().optional(), payload: z.unknown().optional() });
