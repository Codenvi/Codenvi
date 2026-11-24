import { sql } from "drizzle-orm";
import { db, exec, one, rows } from "../db/client";
import { log } from "../lib/log";

export interface ClientInfo { kind: string; version: string; build: number; os: string | null; model: string | null }

export const HEADER_INSTALL_ID = "x-codenvi-install-id";
export const HEADER_CLIENT = "x-codenvi-client";

// desktop/1.4.0+57 (Windows 11 26100)   |   android/1.4.0+42 (Android 14; Pixel 7)
const CLIENT = /^(?<kind>desktop|android)\/(?<ver>[0-9A-Za-z.\-]+)\+(?<build>\d{1,9})(?:\s*\((?<detail>[^)]*)\))?$/;

export function parseClient(header: string | undefined): ClientInfo | null {
  if (!header) return null;
  const m = CLIENT.exec(header.trim());
  if (!m?.groups) return null;
  let os: string | null = null;
  let model: string | null = null;
  if (m.groups.detail !== undefined) {
    const [first, ...rest] = m.groups.detail.split(";");
    os = first.trim();
    if (rest.length) model = rest.join(";").trim();
  }
  return { kind: m.groups.kind, version: m.groups.ver, build: Number(m.groups.build), os, model };
}

const ACTIVE_TTL_MS = 5_000;
const TOUCH_INTERVAL_MS = 60_000;
const active = new Map<string, { active: boolean; at: number }>();
const touched = new Map<string, { version: string; build: number; at: number }>();
let minBuilds = new Map<string, number>();
let minBuildsAt = 0;

/** "Is this install still allowed?" with a short cache (revocation clears it at once). */
export async function isActive(installId: string): Promise<boolean> {
  const c = active.get(installId);
  if (c && Date.now() - c.at < ACTIVE_TTL_MS) return c.active;
  const r = await one<{ revoked_at: Date | null }>(db, sql`SELECT revoked_at FROM installs WHERE id = ${installId}::uuid`);
  const ok = !!r && r.revoked_at === null;
  active.set(installId, { active: ok, at: Date.now() });
  return ok;
}

export function invalidate(installId: string): void {
  active.set(installId, { active: false, at: Date.now() });
  touched.delete(installId);
}

export async function minBuild(kind: string): Promise<number> {
  if (Date.now() - minBuildsAt > 60_000) {
    const r = await rows<{ kind: string; min_build: number }>(db, sql`SELECT kind, min_build FROM app_min_versions`);
    minBuilds = new Map(r.map((x) => [x.kind, x.min_build]));
    minBuildsAt = Date.now();
  }
  return minBuilds.get(kind) ?? 0;
}

/** Records last-seen / version information (docs/design.md §7.2). Never throws. */
export async function touch(installId: string, client: ClientInfo | null, ip: string | null): Promise<void> {
  const now = Date.now();
  const last = touched.get(installId);
  const versionChanged = client !== null && (!last || last.version !== client.version || last.build !== client.build);
  if (!versionChanged && last && now - last.at < TOUCH_INTERVAL_MS) return;
  try {
    if (!client) {
      await exec(db, sql`UPDATE installs SET last_seen_at = now(), last_ip = ${ip}::inet WHERE id = ${installId}::uuid`);
      return;
    }
    const prev = await one<{ app_version: string; app_build: number }>(db, sql`
      UPDATE installs i SET last_seen_at = now(), last_ip = ${ip}::inet,
             app_version = ${client.version}, app_build = ${client.build},
             os_version = coalesce(${client.os}::text, i.os_version), device_model = coalesce(${client.model}::text, i.device_model)
      FROM (SELECT id, app_version, app_build FROM installs WHERE id = ${installId}::uuid FOR UPDATE) old
      WHERE i.id = old.id
      RETURNING old.app_version, old.app_build`);
    if (prev && (prev.app_version !== client.version || prev.app_build !== client.build)) {
      await exec(db, sql`INSERT INTO install_version_history (install_id, app_version, app_build) VALUES (${installId}::uuid, ${client.version}, ${client.build})`);
    }
    touched.set(installId, { version: client.version, build: client.build, at: now });
  } catch (e) {
    log.warn(`Failed to update last-seen for ${installId}`, e);
  }
}
