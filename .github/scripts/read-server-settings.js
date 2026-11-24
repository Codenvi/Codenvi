// Reads the server's deployment settings from the ENV_VARS repository secret (passed in the environment variable
// ENV_VARS), validates them and writes them to $GITHUB_OUTPUT for the later steps of the server job.
//
// ENV_VARS is a list of KEY=VALUE lines. These three lines name the server on the VPS:
//   CODENVI_APP_NAME=codenvi              folder /var/www/<name>, PM2 process, nginx site, admin command <name>-admin,
//                                         and the PostgreSQL database + role (hyphens become underscores)
//   CODENVI_HOST=codenvi.example.com      public host name of the server (nginx + Let's Encrypt)
//   CODENVI_PORT=3005                     local port the server listens on (127.0.0.1 only)
//
// The values are never committed to the repository, so a fork or copy of it does not inherit them.
// JWT_SECRET, the database password and the server password are not needed here: the deploy generates them.
const fs = require("fs");

function fail(message) {
  console.error("::error::" + message);
  process.exit(1);
}

const raw = process.env.ENV_VARS || "";
if (!raw.trim()) fail("The repository secret ENV_VARS is missing or empty (docs/self-hosting.md, section 3).");

const env = {};
for (const line of raw.split(/\r?\n/)) {
  const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
  if (!m) continue;
  env[m[1]] = m[2].replace(/^(["'])(.*)\1$/, "$2"); // allow KEY="value"
}

const name = env.CODENVI_APP_NAME;
const host = env.CODENVI_HOST;
const port = Number(env.CODENVI_PORT);

if (!/^[a-z][a-z0-9-]{1,39}$/.test(name || "")) {
  fail("ENV_VARS needs CODENVI_APP_NAME=<2-40 lowercase letters, digits or hyphens, starting with a letter>, for example codenvi");
}
if (!/^[a-z0-9]([a-z0-9.-]*[a-z0-9])?\.[a-z]{2,}$/.test(host || "")) {
  fail("ENV_VARS needs CODENVI_HOST=<the server's host name>, for example codenvi.example.com");
}
if (!Number.isInteger(port) || port < 1024 || port > 65535) {
  fail("ENV_VARS needs CODENVI_PORT=<a number from 1024 to 65535>, for example 3005");
}

const database = name.replace(/-/g, "_");
fs.appendFileSync(process.env.GITHUB_OUTPUT, `name=${name}\ndomain=${host}\nport=${port}\n`);
console.log(`app=${name} host=${host} port=${port} database=${database}`);
