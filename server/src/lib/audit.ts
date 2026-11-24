import { sql } from "drizzle-orm";
import { exec, type Executor } from "../db/client";

export function audit(ex: Executor, installId: string | null, action: string, ip: string | null | undefined): Promise<number> {
  return exec(ex, sql`INSERT INTO audit_log (install_id, action, ip) VALUES (${installId}::uuid, ${action}, ${ip ?? null}::inet)`);
}
