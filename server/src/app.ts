import express, { type Express } from "express";
import { sql } from "drizzle-orm";
import { authenticate, clientVersion, generalLimiter } from "./auth/middleware";
import { db, one } from "./db/client";
import { errorHandler, sendProblem } from "./lib/http";
import { log } from "./lib/log";
import { authRoutes } from "./routes/auth";
import { deviceRoutes } from "./routes/devices";
import { workRoutes } from "./routes/work";

export const VERSION = process.env.npm_package_version ?? "1.0.0";

async function health(_req: express.Request, res: express.Response): Promise<void> {
  let database = false;
  try {
    database = (await one<{ ok: number }>(db, sql`SELECT 1 AS ok`))?.ok === 1;
  } catch (e) {
    log.warn("Health check: database unreachable", e);
  }
  res.status(database ? 200 : 503).json({ status: database ? "ok" : "degraded", version: VERSION, database });
}

export function createApp(): Express {
  const app = express();
  app.disable("x-powered-by");
  app.set("trust proxy", 1); // nginx on the same machine sets X-Forwarded-For
  app.set("json replacer", (_k: string, v: unknown) => (v === null ? undefined : v)); // null fields are omitted on the wire
  // No CORS: there is no browser client (docs/design.md §7.6).

  app.get("/health", health);
  app.get("/api/v1/health", health);

  // Base64 of a 1 MB ciphertext is ~1.4 MB; the size cap itself is enforced on the decoded length.
  const api = express.Router();
  api.use(express.json({ limit: "3mb" }));
  api.use(authenticate);
  api.use(generalLimiter);
  api.use(clientVersion);
  api.use(authRoutes);
  api.use(deviceRoutes);
  api.use(workRoutes);
  app.use("/api/v1", api);

  // Unknown API routes still require a token, like the rest of the API.
  app.use((req, res) => sendProblem(res, req.auth ? 404 : req.path.startsWith("/api/") ? 401 : 404, req.auth ? "Not found" : "Unauthorized"));
  app.use(errorHandler((m, e) => log.error(m, e)));
  return app;
}
