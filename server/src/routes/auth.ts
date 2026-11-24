import { Router } from "express";
import { sql } from "drizzle-orm";
import { z } from "zod";
import { config } from "../config";
import { clientIp, pairingLimiter, requireAuth, requireDesktop } from "../auth/middleware";
import * as pairing from "../auth/pairing";
import * as tokens from "../auth/tokens";
import { invalidate } from "../auth/guard";
import { db, exec, rows } from "../db/client";
import { audit } from "../lib/audit";
import { badRequest, conflict, forbidden, guidParams, notFound, sendProblem, uuidSchema } from "../lib/http";
import { SyncEntities } from "../lib/envelope";
import { hub } from "../services/hub";
import { INSTALL_COLUMNS, installDto, type InstallRow } from "./dto";

/** Pairing, token refresh, pairing codes and installs (Project.md §4, §7.3). */
export const authRoutes = Router();

const pairSchema = z.object({
  code: z.string().max(100), installId: uuidSchema, kind: z.string(), name: z.string(), appVersion: z.string(),
  appBuild: z.number().int(), osVersion: z.string().nullish(), deviceModel: z.string().nullish(),
});

authRoutes.post("/pair", pairingLimiter, async (req, res) => {
  const body = pairSchema.parse(req.body);
  const { outcome, tokens: issued } = await pairing.redeem(body, clientIp(req));
  switch (outcome) {
    case "ok": return void res.json(issued);
    case "disabled": return sendProblem(res, 403, "Pairing is turned off on this server");
    case "badRequest": return sendProblem(res, 400, "Invalid pairing request");
    default: return sendProblem(res, 401, "Invalid or expired pairing code");
  }
});

authRoutes.post("/token/refresh", async (req, res) => {
  const body = z.object({ installId: uuidSchema, refreshToken: z.string().max(200) }).parse(req.body);
  const { outcome, tokens: issued } = await tokens.refresh(body.installId, body.refreshToken, clientIp(req));
  switch (outcome) {
    case "ok": return void res.json(issued);
    case "reused":
      invalidate(body.installId);
      hub.disconnect(body.installId, "refresh token reuse");
      return sendProblem(res, 401, "This app was disconnected");
    case "revoked": return sendProblem(res, 401, "This app was disconnected");
    default: return sendProblem(res, 401, "Invalid refresh token");
  }
});

// Everything below needs a valid token.
authRoutes.use(requireAuth);

authRoutes.post("/pairing-codes", async (req, res) => {
  if (!requireDesktop(req)) throw forbidden("Only a PC can create pairing codes");
  const body = z.object({ intendedKind: z.enum(["desktop", "android"]).nullish() }).parse(req.body ?? {});
  if (!(await pairing.pairingEnabled())) return sendProblem(res, 403, "Pairing is turned off on this server");
  const { code, expiresAt } = await pairing.createCode(req.auth!.installId, body.intendedKind ?? null);
  await audit(db, req.auth!.installId, "pairing_code.create", clientIp(req));
  res.json({ code, host: config.publicHost, expiresAtMs: expiresAt.getTime() });
});

authRoutes.get("/installs", async (req, res) => {
  const r = await rows<InstallRow>(db, sql`
    SELECT ${sql.raw(INSTALL_COLUMNS)} FROM installs ORDER BY revoked_at NULLS FIRST, paired_at`);
  res.json(r.map((x) => installDto(x, req.auth!.installId)));
});

authRoutes.patch("/installs/:id", guidParams("id"), async (req, res) => {
  const body = z.object({ name: z.string() }).parse(req.body);
  if (!body.name.trim() || body.name.length > 100) throw badRequest("Invalid name");
  const id = req.params.id as string;
  const n = await exec(db, sql`UPDATE installs SET name = ${body.name} WHERE id = ${id}::uuid`);
  if (n === 0) throw notFound();
  hub.syncChanged(SyncEntities.Installs, id);
  res.status(204).end();
});

authRoutes.delete("/installs/:id", guidParams("id"), async (req, res) => {
  const id = req.params.id as string;
  if (id === req.auth!.installId && req.query.confirmSelf !== "true") throw conflict("Revoking this app itself needs confirmSelf=true");
  const n = await db.transaction(async (tx) => {
    const changed = await exec(tx, sql`UPDATE installs SET revoked_at = now() WHERE id = ${id}::uuid AND revoked_at IS NULL`);
    await exec(tx, sql`DELETE FROM refresh_tokens WHERE install_id = ${id}::uuid`);
    return changed;
  });
  if (n === 0) throw notFound();
  invalidate(id);
  hub.disconnect(id, "install revoked");
  await audit(db, req.auth!.installId, `revoke:${id}`, clientIp(req));
  hub.syncChanged(SyncEntities.Installs, id);
  res.status(204).end();
});
