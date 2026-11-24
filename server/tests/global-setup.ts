import { randomBytes } from "node:crypto";
import { drizzle } from "drizzle-orm/node-postgres";
import { migrate } from "drizzle-orm/node-postgres/migrator";
import { Client, Pool } from "pg";

/**
 * Integration tests run only when CODENVI_TEST_PG points to a Postgres superuser connection, for example
 *   CODENVI_TEST_PG=postgresql://postgres:postgres@localhost:5432/postgres
 * A fresh database is created, every migration is applied from zero (exactly like the deploy), and it is dropped at the end.
 */
export default async function setup(): Promise<(() => Promise<void>) | void> {
  process.env.JWT_SECRET = "integration-test-secret-integration-test-secret";
  process.env.CODENVI_PUBLIC_HOST = "codenvi.test";
  process.env.CODENVI_ACCESS_KEY = "Test-Password_for.Codenvi-42";
  process.env.CODENVI_RATE_PAIRING_PER_MIN = "1000";
  process.env.CODENVI_RATE_GENERAL_PER_MIN = "10000";
  process.env.CODENVI_RATE_PROMPTS_PER_MIN = "1000";

  const admin = process.env.CODENVI_TEST_PG;
  if (!admin) {
    process.env.DATABASE_URL = "postgresql://unused:unused@127.0.0.1:1/unused"; // lets config load; DB tests are skipped
    return;
  }

  const dbName = `codenvi_test_${randomBytes(6).toString("hex")}`;
  const client = new Client({ connectionString: admin });
  await client.connect();
  await client.query(`CREATE DATABASE ${dbName}`);
  await client.end();

  const url = new URL(admin);
  url.pathname = `/${dbName}`;
  process.env.DATABASE_URL = url.toString();
  process.env.CODENVI_TEST_DB_READY = "1";

  const pool = new Pool({ connectionString: process.env.DATABASE_URL });
  await migrate(drizzle(pool), { migrationsFolder: "./drizzle" });
  await pool.end();

  return async () => {
    const c = new Client({ connectionString: admin });
    await c.connect();
    await c.query(`DROP DATABASE IF EXISTS ${dbName} WITH (FORCE)`);
    await c.end();
  };
}
