import "dotenv/config";
import path from "node:path";
import { drizzle } from "drizzle-orm/node-postgres";
import { migrate } from "drizzle-orm/node-postgres/migrator";
import { Pool } from "pg";

// Applies every SQL file in ../drizzle that is not yet recorded in drizzle.__drizzle_migrations.
// Used by the deploy (before the new release goes live) and locally: npm run db:migrate
async function main(): Promise<void> {
  const connectionString = process.env.DATABASE_URL;
  if (!connectionString) throw new Error("DATABASE_URL is not set.");
  const pool = new Pool({ connectionString });
  try {
    const migrationsFolder = path.resolve(__dirname, "../drizzle");
    console.log(`[migrate] applying migrations from ${migrationsFolder}`);
    await migrate(drizzle(pool), { migrationsFolder });
    console.log("[migrate] done");
  } finally {
    await pool.end();
  }
}

main().catch((e) => {
  console.error("[migrate] failed:", e instanceof Error ? e.message : e);
  process.exitCode = 1;
});
