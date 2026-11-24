// PM2 prefixes every line with a timestamp (time: true), so plain console output is enough.
const fmt = (level: string, msg: string, extra?: unknown) =>
  extra === undefined ? `[${level}] ${msg}` : `[${level}] ${msg} ${extra instanceof Error ? (extra.stack ?? extra.message) : JSON.stringify(extra)}`;

export const log = {
  info: (msg: string, extra?: unknown) => console.log(fmt("info", msg, extra)),
  warn: (msg: string, extra?: unknown) => console.warn(fmt("warn", msg, extra)),
  error: (msg: string, extra?: unknown) => console.error(fmt("error", msg, extra)),
  debug: (msg: string, extra?: unknown) => { if (process.env.LOG_DEBUG) console.log(fmt("debug", msg, extra)); },
};
