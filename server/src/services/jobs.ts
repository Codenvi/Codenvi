import { randomUUID } from "node:crypto";
import { sql } from "drizzle-orm";
import { config } from "../config";
import { db, exec, exists, one, rows, ms, type Executor, type Tx } from "../db/client";
import { b64Length, fromB64, toB64 } from "../lib/http";
import { createEnvelope, FINAL_JOB_STATUSES, isFinal, JobStatuses, MessageTypes, SyncEntities, ALL_JOB_STATUSES } from "../lib/envelope";
import { log } from "../lib/log";
import { hub, type ClientConnection } from "./hub";

/** Job persistence and delivery (Project.md §7.4 "Delivery guarantee"). */

export const JOB_COLUMNS =
  "id, device_id, project_id, session_id, prompt_message_id, sent_by_install, kind, model, effort, status, " +
  "error_code, enc_error, queued_at, delivered_at, started_at, finished_at";

export interface JobRow {
  id: string; device_id: string; project_id: string; session_id: string; prompt_message_id: string;
  sent_by_install: string | null; kind: string; model: string | null; effort: string | null; status: string;
  error_code: string | null; enc_error: Buffer | null; queued_at: Date; delivered_at: Date | null;
  started_at: Date | null; finished_at: Date | null;
}

const jobSelect = sql.raw(JOB_COLUMNS);

export const jobDto = (j: JobRow) => ({
  id: j.id, deviceId: j.device_id, projectId: j.project_id, sessionId: j.session_id, promptMessageId: j.prompt_message_id,
  kind: j.kind, model: j.model, effort: j.effort, status: j.status, errorCode: j.error_code,
  encError: j.enc_error ? toB64(j.enc_error) : null,
  queuedAtMs: j.queued_at.getTime(), startedAtMs: ms(j.started_at), finishedAtMs: ms(j.finished_at),
});

export async function getJob(ex: Executor, jobId: string): Promise<JobRow | undefined> {
  return one<JobRow>(ex, sql`SELECT ${jobSelect} FROM jobs WHERE id = ${jobId}::uuid`);
}

/** Inserts a message with the next per-session seq. Must run inside a transaction. */
export async function insertMessage(
  tx: Tx, id: string, sessionId: string, role: string, jobId: string | null, ciphertext: Buffer, clientCreatedAt: Date,
): Promise<number> {
  // Serialize seq assignment per session.
  await exec(tx, sql`SELECT 1 FROM sessions WHERE id = ${sessionId}::uuid FOR UPDATE`);
  const r = await one<{ seq: number }>(tx, sql`
    INSERT INTO messages (id, session_id, seq, role, job_id, ciphertext, client_created_at)
    VALUES (${id}::uuid, ${sessionId}::uuid,
            (SELECT coalesce(max(seq), 0) + 1 FROM messages WHERE session_id = ${sessionId}::uuid),
            ${role}, ${jobId}::uuid, ${ciphertext}, ${clientCreatedAt})
    RETURNING seq`);
  return r!.seq;
}

export interface SendPrompt { messageId: string; ciphertext: string; clientCreatedAtMs: number; model?: string | null; effort?: string | null }

export async function createPromptJob(sessionId: string, req: SendPrompt, sentBy: string) {
  const jobId = randomUUID();
  const model = !req.model || req.model === "default" ? null : req.model;
  const effort = !req.effort || req.effort === "default" ? null : req.effort;

  const seq = await db.transaction(async (tx) => {
    const target = await one<{ project_id: string; device_id: string }>(tx, sql`
      SELECT p.id AS project_id, p.device_id FROM sessions s
      JOIN projects p ON p.id = s.project_id
      JOIN devices d ON d.id = p.device_id
      WHERE s.id = ${sessionId}::uuid AND s.deleted_at IS NULL AND p.deleted_at IS NULL AND d.deleted_at IS NULL`);
    if (!target) return null;

    const s = await insertMessage(tx, req.messageId, sessionId, "user", jobId, fromB64(req.ciphertext), new Date(req.clientCreatedAtMs));
    await exec(tx, sql`
      INSERT INTO jobs (id, device_id, project_id, session_id, prompt_message_id, sent_by_install, kind, model, effort, status)
      VALUES (${jobId}::uuid, ${target.device_id}::uuid, ${target.project_id}::uuid, ${sessionId}::uuid, ${req.messageId}::uuid,
              ${sentBy}::uuid, 'prompt', ${model}, ${effort}, 'queued')`);
    await exec(tx, sql`UPDATE sessions SET updated_at = now() WHERE id = ${sessionId}::uuid`);
    return s;
  });
  if (seq === null) return null;

  await tryDispatch(jobId);
  hub.syncChanged(SyncEntities.Messages, sessionId, null, sentBy);
  return { messageId: req.messageId, jobId, seq };
}

/** Sends a queued/delivered job to its device if that device is online. */
export async function tryDispatch(jobId: string): Promise<void> {
  const job = await getJob(db, jobId);
  if (!job || (job.status !== JobStatuses.Queued && job.status !== JobStatuses.Delivered)) return;
  const connection = hub.forDevice(job.device_id);
  if (connection) await sendDispatch(connection, job);
}

/** Re-sends all queued/delivered/waiting_login jobs for a device, oldest first (on every desktop hello). */
export async function resendPending(deviceId: string): Promise<void> {
  const connection = hub.forDevice(deviceId);
  if (!connection) return;
  const jobs = await rows<JobRow>(db, sql`
    SELECT ${jobSelect} FROM jobs WHERE device_id = ${deviceId}::uuid AND status IN ('queued','delivered','waiting_login') ORDER BY queued_at`);
  for (const job of jobs) await sendDispatch(connection, job);
}

/** Re-dispatches jobs that were delivered but never reported running (2-minute rule). */
export async function redispatchStuck(): Promise<void> {
  const jobs = await rows<JobRow>(db, sql`
    SELECT ${jobSelect} FROM jobs
    WHERE status = 'delivered' AND delivered_at < now() - make_interval(secs => ${config.redispatchAfterSeconds}::double precision)
    ORDER BY queued_at`);
  for (const job of jobs) {
    const connection = hub.forDevice(job.device_id);
    if (!connection) continue;
    log.info(`Re-dispatching job ${job.id}`);
    await sendDispatch(connection, job);
  }
}

async function sendDispatch(connection: ClientConnection, job: JobRow): Promise<void> {
  const prompt = await one<{ id: string; ciphertext: Buffer; client_created_at: Date }>(
    db, sql`SELECT id, ciphertext, client_created_at FROM messages WHERE id = ${job.prompt_message_id}::uuid`);
  if (!prompt) return;
  const recent = await rows<{ id: string }>(db, sql`
    SELECT id FROM messages WHERE session_id = ${job.session_id}::uuid AND role IN ('user','assistant') ORDER BY seq DESC LIMIT 20`);

  await exec(db, sql`UPDATE jobs SET delivered_at = now() WHERE id = ${job.id}::uuid AND status IN ('queued','delivered')`);
  connection.send(createEnvelope(MessageTypes.JobDispatch, {
    job: {
      id: job.id, deviceId: job.device_id, projectId: job.project_id, sessionId: job.session_id, promptMessageId: job.prompt_message_id,
      kind: job.kind, model: job.model, effort: job.effort, queuedAtMs: job.queued_at.getTime(),
    },
    prompt: { messageId: prompt.id, ciphertext: toB64(prompt.ciphertext), clientCreatedAtMs: prompt.client_created_at.getTime() },
    recentMessageIds: recent.map((r) => r.id),
  }));
}

export async function ack(deviceId: string, jobId: string): Promise<void> {
  const n = await exec(db, sql`
    UPDATE jobs SET status = 'delivered', delivered_at = now() WHERE id = ${jobId}::uuid AND device_id = ${deviceId}::uuid AND status = 'queued'`);
  if (n > 0) await broadcastStatus(jobId);
}

export interface JobStatusPayload {
  jobId: string; status: string; errorCode?: string | null; encError?: string | null;
}

export async function updateStatus(deviceId: string, p: JobStatusPayload): Promise<void> {
  if (!ALL_JOB_STATUSES.has(p.status) || p.status === JobStatuses.Queued) return;
  const encError = p.encError ? fromB64(p.encError) : null;
  const n = await exec(db, sql`
    UPDATE jobs SET status = ${p.status}::text,
           started_at  = CASE WHEN ${p.status}::text = 'running' THEN coalesce(started_at, now()) ELSE started_at END,
           finished_at = CASE WHEN ${p.status}::text IN ('succeeded','failed','cancelled','rejected_stale') THEN now() ELSE finished_at END,
           error_code  = coalesce(${p.errorCode ?? null}::text, error_code),
           enc_error   = coalesce(${encError}::bytea, enc_error)
    WHERE id = ${p.jobId}::uuid AND device_id = ${deviceId}::uuid
      AND status NOT IN ('succeeded','failed','cancelled','rejected_stale')`);
  if (n > 0) await broadcastStatus(p.jobId);
}

export async function broadcastStatus(jobId: string): Promise<void> {
  const job = await getJob(db, jobId);
  if (!job) return;
  hub.broadcastToAndroids(createEnvelope(MessageTypes.JobStatus, {
    jobId: job.id, status: job.status, startedAtMs: ms(job.started_at), finishedAtMs: ms(job.finished_at),
    errorCode: job.error_code, encError: job.enc_error ? toB64(job.enc_error) : null, sessionId: job.session_id, projectId: job.project_id,
  }));
}

export interface ProgressPayload { jobId: string; sessionId: string; messageId: string; ciphertext: string; clientCreatedAtMs: number }
export interface ResultPayload extends ProgressPayload { commitHash?: string | null; final?: boolean }

const ownsJob = (tx: Tx, jobId: string, deviceId: string, sessionId: string) =>
  exists(tx, sql`SELECT 1 FROM jobs WHERE id = ${jobId}::uuid AND device_id = ${deviceId}::uuid AND session_id = ${sessionId}::uuid`);

export async function storeProgress(deviceId: string, p: ProgressPayload): Promise<void> {
  if (b64Length(p.ciphertext) > config.maxCiphertextBytes) return;
  const ok = await db.transaction(async (tx) => {
    if (!(await ownsJob(tx, p.jobId, deviceId, p.sessionId))) return false;
    await insertMessage(tx, p.messageId, p.sessionId, "progress", p.jobId, fromB64(p.ciphertext), new Date(p.clientCreatedAtMs));
    await exec(tx, sql`
      DELETE FROM messages WHERE session_id = ${p.sessionId}::uuid AND role = 'progress' AND seq <
        (SELECT seq FROM messages WHERE session_id = ${p.sessionId}::uuid AND role = 'progress'
         ORDER BY seq DESC OFFSET ${config.progressKeep - 1} LIMIT 1)`);
    return true;
  });
  if (ok) hub.broadcastToAndroids(createEnvelope(MessageTypes.JobProgress, p));
}

export async function storeResult(deviceId: string, p: ResultPayload): Promise<void> {
  if (b64Length(p.ciphertext) > config.maxCiphertextBytes) return;
  const ok = await db.transaction(async (tx) => {
    if (!(await ownsJob(tx, p.jobId, deviceId, p.sessionId))) return false;
    if (await exists(tx, sql`SELECT 1 FROM messages WHERE id = ${p.messageId}::uuid`)) return true; // idempotent re-send
    await insertMessage(tx, p.messageId, p.sessionId, "assistant", p.jobId, fromB64(p.ciphertext), new Date(p.clientCreatedAtMs));
    await exec(tx, sql`UPDATE sessions SET updated_at = now() WHERE id = ${p.sessionId}::uuid`);
    return true;
  });
  if (ok) hub.broadcastToAndroids(createEnvelope(MessageTypes.JobResult, p));
}

export type CancelOutcome = "notFound" | "alreadyFinished" | "cancelled" | "requested";

export async function cancel(jobId: string): Promise<CancelOutcome> {
  const job = await getJob(db, jobId);
  if (!job) return "notFound";
  if (isFinal(job.status)) return "alreadyFinished";

  const cancelMessage = createEnvelope(MessageTypes.JobCancel, { jobId });
  if (job.status === JobStatuses.Queued || !hub.isDeviceOnline(job.device_id)) {
    await exec(db, sql`
      UPDATE jobs SET status = 'cancelled', finished_at = now()
      WHERE id = ${jobId}::uuid AND status NOT IN ('succeeded','failed','cancelled','rejected_stale')`);
    await broadcastStatus(jobId);
    // Tell the desktop too in case it already holds the job locally.
    hub.sendToDevice(job.device_id, cancelMessage);
    return "cancelled";
  }
  hub.sendToDevice(job.device_id, cancelMessage);
  return "requested";
}

/** Key rotation cancels the phone's pending jobs for that device (Project.md §5.5). */
export function cancelPendingForDevice(tx: Tx, deviceId: string): Promise<number> {
  return exec(tx, sql`
    UPDATE jobs SET status = 'cancelled', error_code = 'key_rotated', finished_at = now()
    WHERE device_id = ${deviceId}::uuid AND status IN ('queued','delivered','waiting_login')`);
}

export { FINAL_JOB_STATUSES };
