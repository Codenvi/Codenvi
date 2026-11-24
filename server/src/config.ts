import "dotenv/config";

// On the VPS the deploy writes PORT, HOST and CODENVI_PUBLIC_HOST into the app's .env (from the ENV_VARS secret).
// For local development set PORT (and optionally CODENVI_PUBLIC_HOST) in server/.env; the default port is 5080.
const isProduction = process.env.NODE_ENV === "production";
const port = Number(process.env.PORT) || 5080;

function required(name: string): string {
  const v = process.env[name];
  if (!v) throw new Error(`${name} is not set (see .env.example).`);
  return v;
}

function int(name: string, fallback: number): number {
  const v = Number(process.env[name]);
  return Number.isFinite(v) && v > 0 ? v : fallback;
}

const jwtSecret = required("JWT_SECRET");
if (Buffer.byteLength(jwtSecret, "utf8") < 32) throw new Error("JWT_SECRET must be at least 32 bytes long.");

if (isProduction && !process.env.CODENVI_PUBLIC_HOST) throw new Error("CODENVI_PUBLIC_HOST is not set (the deploy writes it from CODENVI_HOST).");

// Optional access key = the password of your server: one shared secret (the GitHub secret ENV_VARS) that lets any
// app link to this server, the phone and as many PCs as you like, by typing the host name and this password once
// (docs/self-hosting.md). Each app that uses it gets its OWN credential and can be revoked on its own.
// Any characters are allowed; it is compared exactly as typed (surrounding spaces are ignored). At least 16 characters,
// because a short password could be guessed.
export const MIN_ACCESS_KEY_LENGTH = 16;
const accessKey = (process.env.CODENVI_ACCESS_KEY || "").trim();
if (accessKey && accessKey.length < MIN_ACCESS_KEY_LENGTH) {
  throw new Error(`CODENVI_ACCESS_KEY must be at least ${MIN_ACCESS_KEY_LENGTH} characters long (for example 24 random letters and digits).`);
}

export const config = {
  isProduction,
  port,
  host: process.env.HOST || "127.0.0.1", // nginx proxies to it on the same machine
  databaseUrl: required("DATABASE_URL"),
  jwtSecret,
  /** Public host (and optional :port) put into connection strings, e.g. codenvi.example.com. */
  publicHost: process.env.CODENVI_PUBLIC_HOST || `localhost:${port}`,
  accessKey,
  /** Optional SPKI SHA-256 pin (base64url) for self-signed setups (Q1). */
  pin: process.env.CODENVI_PIN || "",
  pairingCodeMinutes: 15,
  maxCiphertextBytes: 1024 * 1024,
  progressKeep: 200,
  redispatchAfterSeconds: 120,
  purgeAfterDays: 30,
  accessMinutes: 15,
  refreshDays: 90,
  rateLimits: {
    pairingPerMinute: int("CODENVI_RATE_PAIRING_PER_MIN", 5),
    promptsPerMinute: int("CODENVI_RATE_PROMPTS_PER_MIN", 30),
    generalPerMinute: int("CODENVI_RATE_GENERAL_PER_MIN", 300),
  },
};
