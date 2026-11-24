import { randomBytes, timingSafeEqual } from "node:crypto";
import { sql } from "drizzle-orm";
import { config } from "../config";
import { db, exec, exists, one } from "../db/client";
import { audit } from "../lib/audit";
import { encode, normalize } from "../lib/base32";
import { log } from "../lib/log";
import { issue, sha256, type TokenResponse } from "./tokens";

/** One-time pairing codes (Project.md §4.1–4.3). */
export const FAILURES_BEFORE_LOCK = 20;

/** 128-bit random code shown as 26 Crockford Base32 characters. */
export const newCode = (): string => encode(randomBytes(16));
export const hashCode = (code: string): Buffer => sha256(normalize(code));

export async function createCode(createdBy: string, intendedKind: string | null): Promise<{ code: string; expiresAt: Date }> {
  if (intendedKind !== null && intendedKind !== "desktop" && intendedKind !== "android") throw new Error("Invalid kind.");
  const code = newCode();
  const expiresAt = new Date(Date.now() + config.pairingCodeMinutes * 60_000);
  await exec(db, sql`
    INSERT INTO pairing_codes (code_hash, created_by, intended_kind, expires_at)
    VALUES (${hashCode(code)}, ${createdBy}, ${intendedKind}, ${expiresAt})`);
  return { code, expiresAt };
}

/** codenvi://host[:port]/pair?c=CODE[&dn=NAME][&pin=SPKI_SHA256_B64URL] (Project.md §4.1). */
export function connectionStringFor(code: string, deviceNameHint?: string | null): string {
  const parts = [`c=${code}`];
  if (deviceNameHint) parts.push(`dn=${encodeURIComponent(deviceNameHint)}`);
  if (config.pin) parts.push(`pin=${config.pin}`);
  return `codenvi://${config.publicHost}/pair?${parts.join("&")}`;
}

export async function pairingEnabled(): Promise<boolean> {
  const r = await one<{ value: string }>(db, sql`SELECT value FROM server_settings WHERE key = 'pairing_enabled'`);
  return r?.value !== "false";
}

export async function setPairingEnabled(enabled: boolean): Promise<void> {
  await exec(db, sql`
    INSERT INTO server_settings (key, value) VALUES ('pairing_enabled', ${enabled ? "true" : "false"})
    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value`);
}

export interface PairRequest {
  code: string; installId: string; kind: string; name: string; appVersion: string; appBuild: number;
  osVersion?: string | null; deviceModel?: string | null;
}
export type PairOutcome = "ok" | "invalid" | "disabled" | "badRequest";

/** True when `code` is the configured access key / password (constant-time comparison, exactly as typed). */
function isAccessKey(code: string): boolean {
  if (!config.accessKey) return false;
  const given = Buffer.from(code.trim());
  const wanted = Buffer.from(config.accessKey);
  return given.length === wanted.length && timingSafeEqual(given, wanted);
}

type Tx = Parameters<Parameters<typeof db.transaction>[0]>[0];

async function createInstall(tx: Tx, req: PairRequest, ip: string | null, action: string): Promise<TokenResponse> {
  await exec(tx, sql`
    INSERT INTO installs (id, kind, name, app_version, app_build, os_version, device_model, last_ip)
    VALUES (${req.installId}::uuid, ${req.kind}, ${req.name}, ${req.appVersion}, ${req.appBuild},
            ${req.osVersion ?? null}, ${req.deviceModel ?? null}, ${ip}::inet)`);
  await exec(tx, sql`INSERT INTO install_version_history (install_id, app_version, app_build) VALUES (${req.installId}::uuid, ${req.appVersion}, ${req.appBuild})`);
  const tokens = await issue(tx, req.installId, req.kind);
  await audit(tx, req.installId, action, ip);
  return tokens;
}

export async function redeem(req: PairRequest, ip: string | null): Promise<{ outcome: PairOutcome; tokens?: TokenResponse }> {
  if ((req.kind !== "desktop" && req.kind !== "android") || !req.name.trim() || req.name.length > 100 || req.appVersion.length > 50) {
    return { outcome: "badRequest" };
  }
  if (!(await pairingEnabled())) return { outcome: "disabled" };

  // The shared access key (ENV_VARS): links any app, phone or PC, any number of times. Every app gets its own
  // install and credential, so each one can still be revoked separately.
  if (isAccessKey(req.code)) {
    const linked = await db.transaction(async (tx) => {
      if (await exists(tx, sql`SELECT 1 FROM installs WHERE id = ${req.installId}::uuid`)) return { outcome: "badRequest" as const };
      return { outcome: "ok" as const, tokens: await createInstall(tx, req, ip, "pair.access_key") };
    });
    if (linked.outcome === "ok") log.info(`Linked ${req.kind} install ${req.installId} (${req.name}) with the access key`);
    return linked;
  }

  let hash: Buffer;
  try { hash = hashCode(req.code); } catch { await fail(ip); return { outcome: "invalid" }; }

  const result = await db.transaction(async (tx) => {
    const code = await one<{ intended_kind: string | null; expires_at: Date; used_at: Date | null }>(
      tx, sql`SELECT intended_kind, expires_at, used_at FROM pairing_codes WHERE code_hash = ${hash} FOR UPDATE`);
    if (!code || code.used_at || code.expires_at.getTime() < Date.now() || (code.intended_kind && code.intended_kind !== req.kind)) {
      return { outcome: "invalid" as const };
    }
    if (await exists(tx, sql`SELECT 1 FROM installs WHERE id = ${req.installId}::uuid`)) return { outcome: "badRequest" as const };

    const tokens = await createInstall(tx, req, ip, "pair");
    await exec(tx, sql`UPDATE pairing_codes SET used_at = now(), used_by_install = ${req.installId}::uuid WHERE code_hash = ${hash}`);
    return { outcome: "ok" as const, tokens };
  });

  if (result.outcome === "invalid") await fail(ip);
  else if (result.outcome === "ok") log.info(`Paired ${req.kind} install ${req.installId} (${req.name})`);
  return result;
}

/** Records a failure; after 20 failures in an hour pairing is switched off until `codenvi-admin pairing on`. */
async function fail(ip: string | null): Promise<void> {
  await audit(db, null, "pair.failed", ip);
  const r = await one<{ n: number }>(db, sql`SELECT count(*) AS n FROM audit_log WHERE action = 'pair.failed' AND created_at > now() - interval '1 hour'`);
  if ((r?.n ?? 0) >= FAILURES_BEFORE_LOCK && (await pairingEnabled())) {
    await setPairingEnabled(false);
    await audit(db, null, "pairing.locked", ip);
    log.warn(`Pairing locked after ${r?.n} failures in an hour. Run the admin command "pairing on" to re-enable.`);
  }
}
