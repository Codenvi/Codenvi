import http from "node:http";
import { config } from "./config";
import { createApp } from "./app";
import { pool } from "./db/client";
import { log } from "./lib/log";
import { startBackground } from "./services/background";
import { attachWebSocket } from "./ws";

const app = createApp();
const server = http.createServer(app);
const ws = attachWebSocket(server);
const stopBackground = startBackground();

server.listen(config.port, config.host, () => {
  log.info(`Codenvi server listening on ${config.host}:${config.port} (${config.isProduction ? "production" : "development"}), public host ${config.publicHost}`);
});

let stopping = false;
async function shutdown(signal: string): Promise<void> {
  if (stopping) return;
  stopping = true;
  log.info(`${signal} received, shutting down`);
  stopBackground();
  ws.close();
  server.close();
  server.closeAllConnections();
  await pool.end().catch(() => undefined);
  process.exit(0);
}
process.on("SIGINT", () => void shutdown("SIGINT"));
process.on("SIGTERM", () => void shutdown("SIGTERM"));
process.on("unhandledRejection", (e) => log.error("Unhandled rejection", e));
