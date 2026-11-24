import { drizzle } from "drizzle-orm/node-postgres";
import { sql, type SQL } from "drizzle-orm";
import { Pool, types } from "pg";
import { config } from "../config";

// int8 (bigint, count(*)) arrives as a string by default. Our values (seq, counts) fit in a double.
types.setTypeParser(20, (v) => Number(v));

export const pool = new Pool({ connectionString: config.databaseUrl, max: 20 });
export const db = drizzle(pool);

export type Tx = Parameters<Parameters<typeof db.transaction>[0]>[0];
export type Executor = typeof db | Tx;

const TIMESTAMP_OIDS = new Set([1114, 1184]); // timestamp, timestamptz

/**
 * Runs a parameterised query and returns its rows (snake_case column names, bytea as Buffer).
 * Drizzle's node-postgres driver hands timestamps back as strings for its own mappers, so they are turned into Dates here.
 */
export async function rows<T = Record<string, unknown>>(ex: Executor, query: SQL): Promise<T[]> {
  const result = await ex.execute(query);
  const dateColumns = result.fields.filter((f) => TIMESTAMP_OIDS.has(f.dataTypeID)).map((f) => f.name);
  if (dateColumns.length === 0) return result.rows as T[];
  return result.rows.map((row) => {
    const copy = { ...row } as Record<string, unknown>;
    for (const c of dateColumns) if (typeof copy[c] === "string") copy[c] = new Date(copy[c] as string);
    return copy as T;
  });
}

export async function one<T = Record<string, unknown>>(ex: Executor, query: SQL): Promise<T | undefined> {
  return (await rows<T>(ex, query))[0];
}

export async function exec(ex: Executor, query: SQL): Promise<number> {
  const result = await ex.execute(query);
  return result.rowCount ?? 0;
}

export async function exists(ex: Executor, query: SQL): Promise<boolean> {
  const r = await one<{ ok: boolean }>(ex, sql`SELECT EXISTS (${query}) AS ok`);
  return r?.ok === true;
}

/** True for a unique-constraint violation, whether or not Drizzle wrapped the driver error. */
export function isUniqueViolation(e: unknown): boolean {
  const x = e as { code?: string; cause?: { code?: string } };
  return x?.code === "23505" || x?.cause?.code === "23505";
}

export const ms = (d: Date | null | undefined): number | null => (d ? d.getTime() : null);
