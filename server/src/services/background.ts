import { sql } from "drizzle-orm";
import { config } from "../config";
import { db, exec } from "../db/client";
import { log } from "../lib/log";
import { redispatchStuck } from "./jobs";

/** Nightly cleanup (docs/design.md §6.2): soft-deleted rows older than 30 days, expired pairing codes and refresh tokens, old audit entries. */
export async function purge(days: number): Promise<number> {
  let n = 0;
  n += await exec(db, sql`DELETE FROM sessions WHERE deleted_at < now() - make_interval(days => ${days}::int)`);
  n += await exec(db, sql`DELETE FROM projects WHERE deleted_at < now() - make_interval(days => ${days}::int)`);
  n += await exec(db, sql`
    DELETE FROM devices d WHERE d.deleted_at < now() - make_interval(days => ${days}::int)
      AND NOT EXISTS (SELECT 1 FROM projects p WHERE p.device_id = d.id)`);
  n += await exec(db, sql`DELETE FROM pairing_codes WHERE expires_at < now() - interval '1 day'`);
  n += await exec(db, sql`DELETE FROM refresh_tokens WHERE expires_at < now()`);
  n += await exec(db, sql`DELETE FROM audit_log WHERE created_at < now() - interval '365 days'`);
  log.info(`Purge removed ${n} rows`);
  return n;
}

/** Starts the periodic jobs. Returns a function that stops them. */
export function startBackground(): () => void {
  // Every 30 s: re-dispatch jobs stuck in 'delivered' for more than 2 minutes.
  const redispatch = setInterval(() => {
    redispatchStuck().catch((e) => log.error("Re-dispatch pass failed", e));
  }, 30_000);
  // Nightly purge, first run 5 minutes after start.
  const first = setTimeout(() => {
    purge(config.purgeAfterDays).catch((e) => log.error("Purge failed", e));
  }, 5 * 60_000);
  const nightly = setInterval(() => {
    purge(config.purgeAfterDays).catch((e) => log.error("Purge failed", e));
  }, 24 * 3_600_000);
  for (const t of [redispatch, first, nightly]) t.unref();
  return () => { clearInterval(redispatch); clearTimeout(first); clearInterval(nightly); };
}
