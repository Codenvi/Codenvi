import type { NextFunction, Request, RequestHandler, Response } from "express";
import { ipKeyGenerator, rateLimit } from "express-rate-limit";
import { config } from "../config";
import { sendProblem, unauthorized } from "../lib/http";
import { HEADER_CLIENT, HEADER_INSTALL_ID, isActive, minBuild, parseClient, touch } from "./guard";
import { verifyAccessToken } from "./tokens";

export interface AuthInfo { installId: string; kind: string; exp: number }

declare module "express-serve-static-core" {
  interface Request { auth?: AuthInfo }
}

export const clientIp = (req: Request): string | null => {
  const ip = req.ip;
  return ip ? ip.replace(/^::ffff:/, "") : null;
};

/** Reads the Bearer token. A bad, expired or revoked token just leaves `req.auth` empty; `requireAuth` answers 401. */
export const authenticate: RequestHandler = async (req, _res, next) => {
  try {
    const header = req.headers.authorization;
    const token = header?.startsWith("Bearer ") ? header.slice(7).trim() : null;
    if (token) {
      const claims = verifyAccessToken(token);
      if (claims && (await isActive(claims.iid))) req.auth = { installId: claims.iid, kind: claims.kind, exp: claims.exp };
    }
    next();
  } catch (e) {
    next(e);
  }
};

export const requireAuth: RequestHandler = (req, _res, next) => (req.auth ? next() : next(unauthorized()));

export const requireDesktop = (req: Request): boolean => req.auth?.kind === "desktop";

const limitedKey = (req: Request): string => (req.auth ? `i:${req.auth.installId}` : `ip:${ipKeyGenerator(clientIp(req) ?? "unknown")}`);
const onLimit = (_req: Request, res: Response) => sendProblem(res, 429, "Too many requests");

// Limits from Project.md §7.6.
export const pairingLimiter = rateLimit({
  windowMs: 60_000, limit: config.rateLimits.pairingPerMinute, standardHeaders: false, legacyHeaders: false,
  keyGenerator: (req) => ipKeyGenerator(clientIp(req) ?? "unknown"), handler: onLimit,
});
export const promptLimiter = rateLimit({
  windowMs: 60_000, limit: config.rateLimits.promptsPerMinute, standardHeaders: false, legacyHeaders: false,
  keyGenerator: (req) => req.auth?.installId ?? ipKeyGenerator(clientIp(req) ?? "unknown"), handler: onLimit,
});
export const generalLimiter = rateLimit({
  windowMs: 60_000, limit: config.rateLimits.generalPerMinute, standardHeaders: false, legacyHeaders: false,
  keyGenerator: limitedKey, handler: onLimit,
});

/**
 * Reads X-Codenvi-Install-Id / X-Codenvi-Client, answers 426 Upgrade Required for builds below app_min_versions,
 * and records last-seen / version history (Project.md §7.2).
 */
export const clientVersion: RequestHandler = async (req: Request, res: Response, next: NextFunction) => {
  try {
    const client = parseClient(req.headers[HEADER_CLIENT] as string | undefined);
    if (client) {
      const min = await minBuild(client.kind);
      if (client.build < min) {
        res.status(426).type("application/problem+json").send(JSON.stringify({
          type: "https://httpstatuses.io/426", title: "Update Codenvi", status: 426,
          detail: `This ${client.kind} build (${client.build}) is older than the minimum (${min}).`,
        }));
        return;
      }
    }
    if (req.auth) {
      // Header and token must agree when both are present.
      const header = req.headers[HEADER_INSTALL_ID];
      if (typeof header === "string" && header && header.toLowerCase() !== req.auth.installId) {
        res.status(401).end();
        return;
      }
      void touch(req.auth.installId, client, clientIp(req));
    }
    next();
  } catch (e) {
    next(e);
  }
};
