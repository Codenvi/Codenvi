import { randomUUID } from "node:crypto";
import { Router } from "express";
import { sql } from "drizzle-orm";
import { z } from "zod";
import { config } from "../config";
import { requireDesktop, clientIp } from "../auth/middleware";
import { db, exec, exists, one, rows } from "../db/client";
import { audit } from "../lib/audit";
import { SyncEntities } from "../lib/envelope";
import { b64Length, b64Schema, badRequest, conflict, forbidden, fromB64, guidParams, notFound, tooLarge, uuidSchema } from "../lib/http";
import { hub } from "../services/hub";
import * as jobs from "../services/jobs";
import {
  DEVICE_COLUMNS, PROJECT_COLUMNS, SESSION_COLUMNS, deviceDto, projectDto, sessionDto,
  type DeviceRow, type ProjectRow, type SessionRow,
} from "./dto";

/** Devices, projects and sessions (Project.md §7.3). */
export const deviceRoutes = Router();

const deviceCols = sql.raw(DEVICE_COLUMNS);
const projectCols = sql.raw(PROJECT_COLUMNS);
const sessionCols = sql.raw(SESSION_COLUMNS);

const name100 = z.object({ name: z.string() });

export const ownsDevice = (installId: string, isDesktop: boolean, deviceId: string): Promise<boolean> =>
  isDesktop
    ? exists(db, sql`SELECT 1 FROM devices WHERE id = ${deviceId}::uuid AND install_id = ${installId}::uuid AND deleted_at IS NULL`)
    : Promise.resolve(false);

// ---------------- Devices ----------------
deviceRoutes.post("/devices", async (req, res) => {
  if (!requireDesktop(req)) throw forbidden("Only a PC can register as a device");
  const body = z.object({ id: uuidSchema.nullish(), name: z.string(), keyCheck: b64Schema }).parse(req.body);
  const keyCheck = fromB64(body.keyCheck);
  if (!body.name.trim() || body.name.length > 100 || keyCheck.length !== 8) throw badRequest("Invalid device registration");
  const id = body.id ?? randomUUID();
  const installId = req.auth!.installId;

  const owner = await one<{ install_id: string }>(db, sql`SELECT install_id FROM devices WHERE id = ${id}::uuid`);
  if (owner && owner.install_id !== installId) throw conflict("Device id already in use");

  await exec(db, sql`
    INSERT INTO devices (id, install_id, name, key_check) VALUES (${id}::uuid, ${installId}::uuid, ${body.name}, ${keyCheck})
    ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, deleted_at = NULL`);
  await audit(db, installId, "device.register", clientIp(req));
  hub.syncChanged(SyncEntities.Device, id);
  const row = await one<DeviceRow>(db, sql`SELECT ${deviceCols} FROM devices WHERE id = ${id}::uuid`);
  res.json(deviceDto(row!, hub.isDeviceOnline(id)));
});

deviceRoutes.get("/devices", async (_req, res) => {
  const r = await rows<DeviceRow>(db, sql`SELECT ${deviceCols} FROM devices WHERE deleted_at IS NULL ORDER BY created_at`);
  res.json(r.map((d) => deviceDto(d, hub.isDeviceOnline(d.id))));
});

deviceRoutes.patch("/devices/:id", guidParams("id"), async (req, res) => {
  const body = name100.parse(req.body);
  if (!body.name.trim() || body.name.length > 100) throw badRequest("Invalid name");
  const id = req.params.id as string;
  const n = await exec(db, sql`UPDATE devices SET name = ${body.name} WHERE id = ${id}::uuid AND deleted_at IS NULL`);
  if (n === 0) throw notFound();
  hub.syncChanged(SyncEntities.Device, id);
  res.status(204).end();
});

deviceRoutes.post("/devices/:id/key", guidParams("id"), async (req, res) => {
  const id = req.params.id as string;
  if (!(await ownsDevice(req.auth!.installId, requireDesktop(req), id))) throw forbidden();
  const body = z.object({ keyVersion: z.number().int(), keyCheck: b64Schema }).parse(req.body);
  const keyCheck = fromB64(body.keyCheck);
  if (keyCheck.length !== 8 || body.keyVersion < 2) throw badRequest("Invalid key rotation");

  const ok = await db.transaction(async (tx) => {
    const n = await exec(tx, sql`
      UPDATE devices SET key_version = ${body.keyVersion}, key_check = ${keyCheck}
      WHERE id = ${id}::uuid AND key_version < ${body.keyVersion}`);
    if (n === 0) return false;
    await jobs.cancelPendingForDevice(tx, id);
    return true;
  });
  if (!ok) throw conflict("Key version must increase");
  await audit(db, req.auth!.installId, "key.rotate", clientIp(req));
  hub.syncChanged(SyncEntities.Device, id);
  res.status(204).end();
});

deviceRoutes.delete("/devices/:id", guidParams("id"), async (req, res) => {
  const id = req.params.id as string;
  const n = await db.transaction(async (tx) => {
    const changed = await exec(tx, sql`UPDATE devices SET deleted_at = now() WHERE id = ${id}::uuid AND deleted_at IS NULL`);
    await exec(tx, sql`UPDATE projects SET deleted_at = now() WHERE device_id = ${id}::uuid AND deleted_at IS NULL`);
    await exec(tx, sql`UPDATE jobs SET status = 'cancelled', finished_at = now() WHERE device_id = ${id}::uuid AND status IN ('queued','delivered','waiting_login')`);
    return changed;
  });
  if (n === 0) throw notFound();
  await audit(db, req.auth!.installId, "device.delete", clientIp(req));
  hub.syncChanged(SyncEntities.Device, id);
  res.status(204).end();
});

// ---------------- Projects ----------------
deviceRoutes.get("/devices/:id/projects", guidParams("id"), async (req, res) => {
  const r = await rows<ProjectRow>(db, sql`
    SELECT ${projectCols} FROM projects WHERE device_id = ${req.params.id as string}::uuid AND deleted_at IS NULL ORDER BY sort_order, created_at`);
  res.json(r.map(projectDto));
});

deviceRoutes.put("/projects/:id", guidParams("id"), async (req, res) => {
  const id = req.params.id as string;
  const body = z.object({
    deviceId: uuidSchema, encMeta: b64Schema, defaultSessionId: uuidSchema.nullish(), sortOrder: z.number().int(),
  }).parse(req.body);
  if (!(await ownsDevice(req.auth!.installId, requireDesktop(req), body.deviceId))) throw forbidden();
  if (b64Length(body.encMeta) > config.maxCiphertextBytes) throw tooLarge();

  const owner = await one<{ device_id: string }>(db, sql`SELECT device_id FROM projects WHERE id = ${id}::uuid`);
  if (owner && owner.device_id !== body.deviceId) throw conflict("Project belongs to another device");
  await exec(db, sql`
    INSERT INTO projects (id, device_id, enc_meta, default_session_id, sort_order)
    VALUES (${id}::uuid, ${body.deviceId}::uuid, ${fromB64(body.encMeta)}, ${body.defaultSessionId ?? null}::uuid, ${body.sortOrder})
    ON CONFLICT (id) DO UPDATE SET enc_meta = EXCLUDED.enc_meta,
        default_session_id = coalesce(EXCLUDED.default_session_id, projects.default_session_id),
        sort_order = EXCLUDED.sort_order, updated_at = now(), deleted_at = NULL`);
  hub.syncChanged(SyncEntities.Project, id, body.deviceId, req.auth!.installId);
  res.status(204).end();
});

deviceRoutes.patch("/projects/:id", guidParams("id"), async (req, res) => {
  const id = req.params.id as string;
  const body = z.object({
    encMeta: b64Schema.nullish(), sortOrder: z.number().int().nullish(), defaultSessionId: uuidSchema.nullish(),
  }).parse(req.body);
  if (body.encMeta && b64Length(body.encMeta) > config.maxCiphertextBytes) throw tooLarge();
  const r = await one<{ device_id: string }>(db, sql`
    UPDATE projects SET enc_meta = coalesce(${body.encMeta ? fromB64(body.encMeta) : null}::bytea, enc_meta),
           sort_order = coalesce(${body.sortOrder ?? null}::integer, sort_order),
           default_session_id = coalesce(${body.defaultSessionId ?? null}::uuid, default_session_id), updated_at = now()
    WHERE id = ${id}::uuid AND deleted_at IS NULL RETURNING device_id`);
  if (!r) throw notFound();
  hub.syncChanged(SyncEntities.Project, id, r.device_id, req.auth!.installId);
  res.status(204).end();
});

deviceRoutes.delete("/projects/:id", guidParams("id"), async (req, res) => {
  const id = req.params.id as string;
  const r = await one<{ device_id: string }>(db, sql`SELECT device_id FROM projects WHERE id = ${id}::uuid`);
  if (!r) throw notFound();
  if (!(await ownsDevice(req.auth!.installId, requireDesktop(req), r.device_id))) throw forbidden("Projects are deleted from their PC");
  // Hard delete: sessions, messages, jobs and commit records go with it (folder and branch are untouched).
  await exec(db, sql`DELETE FROM projects WHERE id = ${id}::uuid`);
  hub.syncChanged(SyncEntities.Project, id, r.device_id, req.auth!.installId);
  res.status(204).end();
});

// ---------------- Sessions ----------------
deviceRoutes.get("/projects/:id/sessions", guidParams("id"), async (req, res) => {
  const r = await rows<SessionRow>(db, sql`
    SELECT ${sessionCols} FROM sessions WHERE project_id = ${req.params.id as string}::uuid AND deleted_at IS NULL ORDER BY created_at`);
  res.json(r.map(sessionDto));
});

deviceRoutes.put("/sessions/:id", guidParams("id"), async (req, res) => {
  const id = req.params.id as string;
  const body = z.object({ projectId: uuidSchema, encMeta: b64Schema }).parse(req.body);
  if (b64Length(body.encMeta) > config.maxCiphertextBytes) throw tooLarge();
  if (!(await exists(db, sql`SELECT 1 FROM projects WHERE id = ${body.projectId}::uuid AND deleted_at IS NULL`))) throw notFound("Project not found");
  const owner = await one<{ project_id: string }>(db, sql`SELECT project_id FROM sessions WHERE id = ${id}::uuid`);
  if (owner && owner.project_id !== body.projectId) throw conflict("Session belongs to another project");
  await exec(db, sql`
    INSERT INTO sessions (id, project_id, enc_meta) VALUES (${id}::uuid, ${body.projectId}::uuid, ${fromB64(body.encMeta)})
    ON CONFLICT (id) DO UPDATE SET enc_meta = EXCLUDED.enc_meta, updated_at = now(), deleted_at = NULL`);
  hub.syncChanged(SyncEntities.Session, id, body.projectId, req.auth!.installId);
  res.status(204).end();
});

deviceRoutes.patch("/sessions/:id", guidParams("id"), async (req, res) => {
  const id = req.params.id as string;
  const body = z.object({ encMeta: b64Schema }).parse(req.body);
  if (b64Length(body.encMeta) > config.maxCiphertextBytes) throw tooLarge();
  const r = await one<{ project_id: string }>(db, sql`
    UPDATE sessions SET enc_meta = ${fromB64(body.encMeta)}, updated_at = now() WHERE id = ${id}::uuid AND deleted_at IS NULL RETURNING project_id`);
  if (!r) throw notFound();
  hub.syncChanged(SyncEntities.Session, id, r.project_id, req.auth!.installId);
  res.status(204).end();
});

deviceRoutes.delete("/sessions/:id", guidParams("id"), async (req, res) => {
  const id = req.params.id as string;
  const projectId = await db.transaction(async (tx) => {
    const r = await one<{ project_id: string }>(tx, sql`
      UPDATE sessions SET deleted_at = now() WHERE id = ${id}::uuid AND deleted_at IS NULL RETURNING project_id`);
    // Messages are hard-deleted immediately (jobs cascade with their prompt).
    await exec(tx, sql`DELETE FROM messages WHERE session_id = ${id}::uuid`);
    await exec(tx, sql`UPDATE projects SET default_session_id = NULL WHERE default_session_id = ${id}::uuid`);
    return r?.project_id ?? null;
  });
  if (!projectId) throw notFound();
  hub.syncChanged(SyncEntities.Session, id, projectId, req.auth!.installId);
  res.status(204).end();
});
