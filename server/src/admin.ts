import { sql } from "drizzle-orm";
import { config } from "./config";
import { db, exec, pool, rows } from "./db/client";
import { audit } from "./lib/audit";
import * as pairing from "./auth/pairing";
import { purge } from "./services/background";
import type { InstallRow } from "./routes/dto";

// codenvi-admin: emergency and pairing commands on the VPS (docs/design.md §4.3, §7.7). Run on the server, from the app directory:
//   cd /var/www/codenvi && node dist/admin.js <command>        (or: npm run admin -- <command>)
const USAGE = `Usage: node dist/admin.js <command>

  pair [--name "Home PC"] [--kind desktop|android]   Print a one-time connection string (15 min)
  installs                                           List paired installs
  revoke <install-id>                                Revoke one install
  revoke --all                                       Revoke every install
  pairing on|off                                     Allow or block new pairings
  purge                                              Run the nightly cleanup now

To invalidate every access token at once, change the JWT_SECRET value in the ENV_VARS repository secret and re-run the deploy.`;

const option = (args: string[], name: string): string | undefined => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
};
const trunc = (s: string, n: number) => (s.length <= n ? s : `${s.slice(0, n - 1)}…`);

async function main(args: string[]): Promise<number> {
  if (args.length === 0 || ["-h", "--help", "help"].includes(args[0])) {
    console.log(USAGE);
    return args.length === 0 ? 2 : 0;
  }

  switch (args[0]) {
    case "pair": {
      const name = option(args, "--name");
      const kind = option(args, "--kind") ?? "desktop";
      if (kind !== "desktop" && kind !== "android") { console.error("--kind must be desktop or android"); return 2; }
      if (!(await pairing.pairingEnabled())) console.error("Warning: pairing is off. Run `pairing on` first.");
      const { code, expiresAt } = await pairing.createCode("cli", kind);
      await audit(db, null, "pairing_code.create.cli", null);
      console.log(`\nConnection string for a ${kind} (single use, expires ${expiresAt.toISOString()}):\n\n  ${pairing.connectionStringFor(code, name)}\n`);
      return 0;
    }
    case "installs": {
      const r = await rows<InstallRow>(db, sql`
        SELECT id, kind, name, app_version, app_build, host(last_ip) AS last_ip, paired_at, last_seen_at, revoked_at FROM installs ORDER BY paired_at`);
      console.log(`${"ID".padEnd(36)}  ${"KIND".padEnd(8)} ${"NAME".padEnd(20)} ${"VERSION".padEnd(14)} ${"LAST SEEN (UTC)".padEnd(20)} ${"LAST IP".padEnd(16)} STATUS`);
      for (const i of r) {
        console.log(`${i.id}  ${i.kind.padEnd(8)} ${trunc(i.name, 20).padEnd(20)} ${trunc(`${i.app_version}+${i.app_build}`, 14).padEnd(14)} ` +
          `${i.last_seen_at.toISOString().slice(0, 19).replace("T", " ")}  ${(i.last_ip ?? "-").padEnd(16)} ${i.revoked_at ? "revoked" : "active"}`);
      }
      return 0;
    }
    case "revoke": {
      if (args.length < 2) { console.error(USAGE); return 2; }
      const target = args[1];
      if (target !== "--all" && !/^[0-9a-f-]{36}$/i.test(target)) { console.error(`Not an install id: ${target}`); return 2; }
      const n = await db.transaction(async (tx) => {
        let changed: number;
        if (target === "--all") {
          changed = await exec(tx, sql`UPDATE installs SET revoked_at = now() WHERE revoked_at IS NULL`);
          await exec(tx, sql`DELETE FROM refresh_tokens`);
        } else {
          changed = await exec(tx, sql`UPDATE installs SET revoked_at = now() WHERE id = ${target}::uuid AND revoked_at IS NULL`);
          await exec(tx, sql`DELETE FROM refresh_tokens WHERE install_id = ${target}::uuid`);
        }
        await audit(tx, null, `revoke.cli:${target}`, null);
        return changed;
      });
      console.log(`Revoked ${n} install(s). Their access tokens stop working within seconds; open connections are closed on their next message.`);
      return 0;
    }
    case "pairing": {
      if (args[1] !== "on" && args[1] !== "off") { console.error(USAGE); return 2; }
      await pairing.setPairingEnabled(args[1] === "on");
      await audit(db, null, `pairing.${args[1]}`, null);
      console.log(`Pairing is now ${args[1]}.`);
      return 0;
    }
    case "purge":
      await purge(config.purgeAfterDays);
      console.log("Purge done.");
      return 0;
    default:
      console.error(USAGE);
      return 2;
  }
}

main(process.argv.slice(2))
  .then((code) => { process.exitCode = code; })
  .catch((e) => { console.error(e instanceof Error ? e.message : e); process.exitCode = 1; })
  .finally(() => pool.end());
