import { createHash, randomBytes } from "node:crypto";
import jwt from "jsonwebtoken";
import { sql } from "drizzle-orm";
import { config } from "../config";
import { db, exec, one, ms, type Executor } from "../db/client";
import { audit } from "../lib/audit";
import { log } from "../lib/log";

const ISSUER = "codenvi";
const AUDIENCE = "codenvi-apps";

export interface AccessClaims { iid: string; kind: string; exp: number }
export interface TokenResponse {
  accessToken: string; accessExpiresAtMs: number; refreshToken: string; refreshExpiresAtMs: number; installId: string;
}

export const sha256 = (text: string): Buffer => createHash("sha256").update(text, "utf8").digest();
export const newRefreshToken = (): string => randomBytes(32).toString("base64url");

export function createAccessToken(installId: string, kind: string): { token: string; expiresAt: Date } {
  const expiresAt = new Date(Date.now() + config.accessMinutes * 60_000);
  const token = jwt.sign({ iid: installId, kind }, config.jwtSecret, {
    algorithm: "HS256", issuer: ISSUER, audience: AUDIENCE,
    notBefore: -5, expiresIn: config.accessMinutes * 60,
  });
  return { token, expiresAt };
}

/** Returns the claims, or null when the signature, issuer, audience or lifetime is wrong (30 s clock skew). */
export function verifyAccessToken(token: string): AccessClaims | null {
  try {
    const p = jwt.verify(token, config.jwtSecret, {
      algorithms: ["HS256"], issuer: ISSUER, audience: AUDIENCE, clockTolerance: 30,
    }) as jwt.JwtPayload;
    if (typeof p.iid !== "string" || typeof p.kind !== "string" || typeof p.exp !== "number") return null;
    return { iid: p.iid, kind: p.kind, exp: p.exp };
  } catch {
    return null;
  }
}

/** Issues an access + refresh pair inside an existing transaction. */
export async function issue(ex: Executor, installId: string, kind: string): Promise<TokenResponse> {
  return (await issueWithId(ex, installId, kind)).tokens;
}

async function issueWithId(ex: Executor, installId: string, kind: string): Promise<{ tokens: TokenResponse; refreshId: string }> {
  const refresh = newRefreshToken();
  const refreshExpires = new Date(Date.now() + config.refreshDays * 86_400_000);
  const row = await one<{ id: string }>(ex, sql`
    INSERT INTO refresh_tokens (install_id, token_hash, expires_at) VALUES (${installId}::uuid, ${sha256(refresh)}, ${refreshExpires})
    RETURNING id`);
  const { token, expiresAt } = createAccessToken(installId, kind);
  return {
    tokens: { accessToken: token, accessExpiresAtMs: expiresAt.getTime(), refreshToken: refresh, refreshExpiresAtMs: refreshExpires.getTime(), installId },
    refreshId: row!.id,
  };
}

/**
 * How long after a rotation the old refresh token may still be retried, as long as the token issued for it was
 * never used. Covers a refresh whose response the phone never received (network drop, app closed mid-request).
 */
export const REFRESH_RETRY_WINDOW_MS = 24 * 3_600_000;

export type RefreshOutcome = "ok" | "invalid" | "reused" | "revoked";

/**
 * Rotating refresh (Project.md §4.3).
 * - A fresh token is exchanged for a new pair and marked used.
 * - A used token whose successor was never used, within 24 h of the rotation, is a lost response: the unused
 *   successor is dropped and a new pair is issued, so the app stays connected.
 * - Any other reuse (the successor was already used, i.e. two parties hold the token) revokes the install.
 */
export async function refresh(installId: string, refreshToken: string, ip: string | null): Promise<{ outcome: RefreshOutcome; tokens?: TokenResponse }> {
  const hash = sha256(refreshToken);
  return db.transaction(async (tx) => {
    const row = await one<{ id: string; install_id: string; expires_at: Date; used_at: Date | null; replaced_by: string | null }>(
      tx, sql`SELECT id, install_id, expires_at, used_at, replaced_by FROM refresh_tokens WHERE token_hash = ${hash} FOR UPDATE`);
    if (!row || row.install_id !== installId) return { outcome: "invalid" as const };

    const install = await one<{ id: string; kind: string; revoked_at: Date | null }>(
      tx, sql`SELECT id, kind, revoked_at FROM installs WHERE id = ${row.install_id}::uuid FOR UPDATE`);
    if (!install) return { outcome: "invalid" as const };
    if (install.revoked_at) return { outcome: "revoked" as const };
    if (row.expires_at.getTime() < Date.now()) return { outcome: "invalid" as const };

    if (row.used_at) {
      const successor = row.replaced_by
        ? await one<{ id: string; used_at: Date | null }>(tx, sql`SELECT id, used_at FROM refresh_tokens WHERE id = ${row.replaced_by}::uuid FOR UPDATE`)
        : undefined;
      const lostResponse = !!successor && successor.used_at === null && Date.now() - row.used_at.getTime() < REFRESH_RETRY_WINDOW_MS;
      if (lostResponse) {
        await exec(tx, sql`DELETE FROM refresh_tokens WHERE id = ${successor!.id}::uuid`);
        const next = await issueWithId(tx, install.id, install.kind);
        await exec(tx, sql`UPDATE refresh_tokens SET replaced_by = ${next.refreshId}::uuid WHERE id = ${row.id}::uuid`);
        await audit(tx, install.id, "refresh.retry", ip);
        log.info(`Refresh retried for install ${install.id} (previous response was not received)`);
        return { outcome: "ok" as const, tokens: next.tokens };
      }
      await exec(tx, sql`UPDATE installs SET revoked_at = now() WHERE id = ${install.id}::uuid`);
      await exec(tx, sql`DELETE FROM refresh_tokens WHERE install_id = ${install.id}::uuid`);
      await audit(tx, install.id, "refresh.reuse_revoked", ip);
      log.warn(`Refresh token reuse for install ${install.id}; install revoked`);
      return { outcome: "reused" as const };
    }

    const next = await issueWithId(tx, install.id, install.kind);
    await exec(tx, sql`UPDATE refresh_tokens SET used_at = now(), replaced_by = ${next.refreshId}::uuid WHERE id = ${row.id}::uuid`);
    return { outcome: "ok" as const, tokens: next.tokens };
  });
}

export { ms };
