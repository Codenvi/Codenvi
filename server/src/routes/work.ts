import { Router } from "express";
import { sql } from "drizzle-orm";
import { z } from "zod";
import { config } from "../config";
import { promptLimiter, requireDesktop } from "../auth/middleware";
import { db, exec, exists, isUniqueViolation, one, rows } from "../db/client";
import { createEnvelope, MessageTypes, SyncEntities } from "../lib/envelope";
import {
  b64Length, b64Schema, badRequest, conflict, forbidden, fromB64, guidParams, notFound, sendProblem, toB64, tooLarge, uuidSchema,
} from "../lib/http";
import { registerControl } from "../services/control";
import { hub } from "../services/hub";
import * as jobs from "../services/jobs";
import { ownsDevice } from "./devices";

/** Messages, jobs, commits, control commands and the model catalog (docs/design.md §7.3). */
export const workRoutes = Router();

// ---------------- Messages ----------------
workRoutes.get("/sessions/:id/messages", guidParams("id"), async (req, res) => {
  const take = Math.min(Math.max(Number.parseInt(String(req.query.limit ?? "100"), 10) || 100, 1), 500);
  const after = Number.parseInt(String(req.query.afterSeq ?? "0"), 10) || 0;
  const r = await rows<{
    id: string; session_id: string; seq: number; role: string; job_id: string | null; ciphertext: Buffer;
    client_created_at: Date; created_at: Date;
  }>(db, sql`
    SELECT id, session_id, seq, role, job_id, ciphertext, client_created_at, created_at
    FROM messages WHERE session_id = ${req.params.id as string}::uuid AND seq > ${after} ORDER BY seq LIMIT ${take}`);
  res.json(r.map((m) => ({
    id: m.id, sessionId: m.session_id, seq: m.seq, role: m.role, jobId: m.job_id, ciphertext: toB64(m.ciphertext),
    clientCreatedAtMs: m.client_created_at.getTime(), createdAtMs: m.created_at.getTime(),
  })));
});

workRoutes.post("/sessions/:id/messages", guidParams("id"), promptLimiter, async (req, res) => {
  const body = z.object({
    messageId: uuidSchema, ciphertext: b64Schema, clientCreatedAtMs: z.number().int(),
    model: z.string().nullish(), effort: z.string().nullish(),
  }).parse(req.body);
  const size = b64Length(body.ciphertext);
  if (size > config.maxCiphertextBytes) throw tooLarge();
  if (size < 31) throw badRequest("Invalid message");
  if (body.model && body.model !== "default") {
    if (!(await exists(db, sql`SELECT 1 FROM model_catalog WHERE alias = ${body.model} AND enabled`))) throw badRequest("Unknown model");
  }
  try {
    const result = await jobs.createPromptJob(req.params.id as string, body, req.auth!.installId);
    if (!result) throw notFound("Session not found");
    res.json(result);
  } catch (e) {
    if (isUniqueViolation(e)) throw conflict("Message already exists");
    throw e;
  }
});

workRoutes.delete("/sessions/:id/messages", guidParams("id"), async (req, res) => {
  const id = req.params.id as string;
  if (!(await exists(db, sql`SELECT 1 FROM sessions WHERE id = ${id}::uuid`))) throw notFound();
  await exec(db, sql`DELETE FROM messages WHERE session_id = ${id}::uuid`);
  hub.syncChanged(SyncEntities.Messages, id, null, req.auth!.installId);
  res.status(204).end();
});

// ---------------- Jobs ----------------
const jobCols = sql.raw(jobs.JOB_COLUMNS);

workRoutes.get("/sessions/:id/jobs", guidParams("id"), async (req, res) => {
  const r = await rows<jobs.JobRow>(db, sql`
    SELECT ${jobCols} FROM jobs WHERE session_id = ${req.params.id as string}::uuid ORDER BY queued_at DESC LIMIT 200`);
  res.json(r.map(jobs.jobDto));
});

workRoutes.get("/devices/:id/jobs/active", guidParams("id"), async (req, res) => {
  const r = await rows<jobs.JobRow>(db, sql`
    SELECT ${jobCols} FROM jobs WHERE device_id = ${req.params.id as string}::uuid
      AND status IN ('queued','delivered','running','waiting_login') ORDER BY queued_at`);
  res.json(r.map(jobs.jobDto));
});

workRoutes.post("/jobs/:id/cancel", guidParams("id"), async (req, res) => {
  switch (await jobs.cancel(req.params.id as string)) {
    case "notFound": throw notFound();
    case "alreadyFinished": throw conflict("Job already finished");
    default: res.status(202).end();
  }
});

// ---------------- Commits ----------------
workRoutes.get("/projects/:id/commits", guidParams("id"), async (req, res) => {
  const r = await rows<{
    id: string; project_id: string; job_id: string | null; git_hash: string; kind: string; enc_meta: Buffer;
    reverted_by: string | null; created_at: Date;
  }>(db, sql`
    SELECT id, project_id, job_id, git_hash, kind, enc_meta, reverted_by, created_at
    FROM commits WHERE project_id = ${req.params.id as string}::uuid ORDER BY created_at DESC LIMIT 500`);
  res.json(r.map((c) => ({
    id: c.id, projectId: c.project_id, jobId: c.job_id, gitHash: c.git_hash.trim(), kind: c.kind, encMeta: toB64(c.enc_meta),
    revertedBy: c.reverted_by, createdAtMs: c.created_at.getTime(),
  })));
});

workRoutes.put("/projects/:id/commits/:commitId", guidParams("id", "commitId"), async (req, res) => {
  const id = req.params.id as string;
  const commitId = req.params.commitId as string;
  const body = z.object({
    jobId: uuidSchema.nullish(), gitHash: z.string(), kind: z.string(), encMeta: b64Schema, revertedBy: uuidSchema.nullish(),
  }).parse(req.body);
  const project = await one<{ device_id: string }>(db, sql`SELECT device_id FROM projects WHERE id = ${id}::uuid`);
  if (!project) throw notFound();
  if (!(await ownsDevice(req.auth!.installId, requireDesktop(req), project.device_id))) throw forbidden();
  if (body.gitHash.length !== 40 || !["task", "rollback", "baseline"].includes(body.kind)) throw badRequest("Invalid commit");
  await exec(db, sql`
    INSERT INTO commits (id, project_id, job_id, git_hash, kind, enc_meta, reverted_by)
    VALUES (${commitId}::uuid, ${id}::uuid, (SELECT j.id FROM jobs j WHERE j.id = ${body.jobId ?? null}::uuid), ${body.gitHash},
            ${body.kind}, ${fromB64(body.encMeta)}, ${body.revertedBy ?? null}::uuid)
    ON CONFLICT (id) DO UPDATE SET enc_meta = EXCLUDED.enc_meta, reverted_by = EXCLUDED.reverted_by`);
  hub.syncChanged(SyncEntities.Commits, id, null, req.auth!.installId);
  res.status(204).end();
});

// ---------------- Control ----------------
workRoutes.post("/projects/:id/control", guidParams("id"), promptLimiter, async (req, res) => {
  const id = req.params.id as string;
  const body = z.object({ requestId: uuidSchema, ciphertext: b64Schema, clientCreatedAtMs: z.number().int() }).parse(req.body);
  if (b64Length(body.ciphertext) > config.maxCiphertextBytes) throw tooLarge();
  const p = await one<{ device_id: string }>(db, sql`SELECT device_id FROM projects WHERE id = ${id}::uuid AND deleted_at IS NULL`);
  if (!p) throw notFound();
  const device = hub.forDevice(p.device_id);
  if (!device) return sendProblem(res, 409, "Device offline");
  registerControl(body.requestId, req.auth!.installId, p.device_id);
  device.send(createEnvelope(MessageTypes.ControlRequest, {
    requestId: body.requestId, deviceId: p.device_id, projectId: id, ciphertext: body.ciphertext, clientCreatedAtMs: body.clientCreatedAtMs,
  }));
  res.status(202).end();
});

// ---------------- Catalog ----------------
workRoutes.get("/catalog/models", async (_req, res) => {
  const r = await rows<{ alias: string; label: string; efforts: string[]; enabled: boolean; sort_order: number }>(
    db, sql`SELECT alias, label, efforts, enabled, sort_order FROM model_catalog WHERE enabled ORDER BY sort_order`);
  res.json(r.map((m) => ({ alias: m.alias, label: m.label, efforts: m.efforts, enabled: m.enabled, sortOrder: m.sort_order })));
});
