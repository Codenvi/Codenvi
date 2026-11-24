import type { NextFunction, Request, RequestHandler, Response } from "express";
import { z, ZodError } from "zod";

/** An error whose title is safe to show to the client (RFC 7807 ProblemDetails). Never carries internals. */
export class HttpError extends Error {
  constructor(public readonly status: number, public readonly title: string) {
    super(title);
  }
}

export const notFound = (what = "Not found") => new HttpError(404, what);
export const forbidden = (why = "Not allowed for this install") => new HttpError(403, why);
export const badRequest = (why: string) => new HttpError(400, why);
export const conflict = (why: string) => new HttpError(409, why);
export const tooLarge = () => new HttpError(413, "Ciphertext too large");
export const unauthorized = (why = "Unauthorized") => new HttpError(401, why);

export function sendProblem(res: Response, status: number, title: string): void {
  res.status(status).type("application/problem+json").send(JSON.stringify({ type: "about:blank", title, status }));
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const isUuid = (v: unknown): v is string => typeof v === "string" && UUID.test(v);

/** Route param that must be a GUID, otherwise the route does not exist (404), like ASP.NET's {id:guid}. */
export function guidParams(...names: string[]): RequestHandler {
  return (req, _res, next) => {
    for (const n of names) {
      const v = req.params[n];
      if (!isUuid(v)) return next("route");
      req.params[n] = v.toLowerCase();
    }
    next();
  };
}

export const uuidSchema = z.string().regex(UUID).transform((v) => v.toLowerCase());
const BASE64 = /^[A-Za-z0-9+/]*={0,2}$/;
/** Standard Base64 string. Kept as a string while it is only forwarded; call `fromB64` to get bytes. */
export const b64Schema = z.string().regex(BASE64);
export const fromB64 = (s: string): Buffer => Buffer.from(s, "base64");
export const toB64 = (b: Buffer | Uint8Array): string => Buffer.from(b).toString("base64");
export const b64Length = (s: string): number => Math.floor((s.length * 3) / 4) - (s.endsWith("==") ? 2 : s.endsWith("=") ? 1 : 0);

/** Wire JSON: camelCase, null fields omitted (docs/protocol.md). */
export const wire = (value: unknown): string => JSON.stringify(value, (_k, v) => (v === null ? undefined : v));

export function errorHandler(log: (msg: string, err: unknown) => void) {
  return (err: unknown, _req: Request, res: Response, _next: NextFunction): void => {
    if (res.headersSent) return;
    if (err instanceof HttpError) return sendProblem(res, err.status, err.title);
    if (err instanceof ZodError) return sendProblem(res, 400, "Invalid request");
    const e = err as { type?: string; status?: number };
    if (e?.type === "entity.too.large") return sendProblem(res, 413, "Request too large");
    if (e?.type === "entity.parse.failed" || e?.type === "encoding.unsupported") return sendProblem(res, 400, "Invalid request");
    log("Unhandled error", err);
    sendProblem(res, 500, "An error occurred while processing your request.");
  };
}
