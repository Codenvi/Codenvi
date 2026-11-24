import { defineConfig } from "drizzle-kit";

// Run from server/: npm run db:generate. SQL migrations go to ./drizzle and are applied by dist/migrate.js
// (locally: npm run db:migrate; on the VPS: by the deploy, before the new release goes live).
export default defineConfig({
  dialect: "postgresql",
  schema: "./src/db/schema.ts",
  out: "./drizzle",
});
