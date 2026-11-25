#!/usr/bin/env bash
# =============================================================================
# Codenvi server deploy. Runs ON THE VPS, as root, started by .github/workflows/server.yml.
#
# Input (environment; the values come from the ENV_VARS secret and were validated by the workflow):
#   APP_NAME   folder /var/www/<APP_NAME>, PM2 process, nginx vhost, admin command <APP_NAME>-admin
#   DOMAIN     public host name (nginx server_name, Let's Encrypt certificate)
#   PORT       local port the server listens on (127.0.0.1 only)
#   WORK       upload folder with release.tgz and env_vars (the ENV_VARS secret)
# The database and its role are named after the app (hyphens become underscores).
#
# KEYS. Nothing but the four names above has to be configured. JWT_SECRET, the database password and the server
# password (CODENVI_ACCESS_KEY) are generated on the first deploy and kept in /var/www/<APP_NAME>/shared/generated.env,
# so later deploys reuse them. A value put into ENV_VARS (JWT_SECRET, CODENVI_ACCESS_KEY) wins over the generated one.
# Generated values are never printed to the deploy log; read the server password with `<APP_NAME>-admin password`.
#
# ISOLATION. The VPS may host other projects. This script only ever creates or changes things that belong to
# this app, and it checks that BEFORE it changes anything:
#   - /var/www/<APP_NAME>, the PM2 process <APP_NAME>, /etc/nginx/sites-{available,enabled}/<APP_NAME>,
#     the certificate for <DOMAIN>, the database + role <DB_NAME>, /usr/local/bin/<APP_NAME>-admin.
#   - On the first deploy all of these must be unused; afterwards they must carry this app's marker or owner.
#   - The port must be free or held by this app's own process. The host name must not be served by another vhost.
#   - Never: firewall changes, apt upgrades, other vhosts, other PM2 processes, other databases or roles,
#     PostgreSQL/nginx configuration, Node.js upgrades. nginx is reloaded only after `nginx -t` passes.
# Each deploy replaces the code in /var/www/<APP_NAME> in place (dist/, drizzle/, demo/, node_modules/): no release
# folders, no symlink, no rollback bookkeeping. Migrations run from a staging folder BEFORE the old code is replaced,
# so a failed migration leaves the running server untouched.
# =============================================================================
set -euo pipefail

APP_NAME="${APP_NAME:?}"; DOMAIN="${DOMAIN:?}"; PORT="${PORT:?}"; WORK="${WORK:?}"
DB_NAME="${APP_NAME//-/_}"

APP_DIR="/var/www/$APP_NAME"
MARKER="$APP_DIR/.codenvi-app"
ENV_FILE="$APP_DIR/shared/.env"
VHOST="/etc/nginx/sites-available/$APP_NAME"
VHOST_ENABLED="/etc/nginx/sites-enabled/$APP_NAME"
OWN_TAG="managed-by: codenvi-deploy app=$APP_NAME"
ADMIN_BIN="/usr/local/bin/$APP_NAME-admin"
KEEP_BACKUPS=10

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m[ok]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warning]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[stopped]\033[0m %s\n' "$*" >&2; exit 1; }

cleanup() { rm -rf -- "$WORK"; }
trap cleanup EXIT

[[ $EUID -eq 0 ]] || die "Run as root (the workflow uses sudo when VPS_USER is not root)."
cd /   # psql/pg_dump run as the postgres user, which cannot read the SSH user's home

# ---------------------------------------------------------------------------- #
#  0. Validate input                                                           #
# ---------------------------------------------------------------------------- #
[[ "$APP_NAME" =~ ^[a-z][a-z0-9-]{1,39}$ ]] || die "CODENVI_APP_NAME must be 2-40 lowercase letters, digits or hyphens."
case "$DB_NAME" in postgres|template0|template1|root) die "CODENVI_APP_NAME '$APP_NAME' is reserved: it would name the database '$DB_NAME'." ;; esac
[[ "$PORT" =~ ^[0-9]+$ ]] && (( PORT >= 1024 && PORT <= 65535 )) || die "CODENVI_PORT must be 1024-65535."
[[ "$DOMAIN" =~ ^[a-z0-9]([a-z0-9.-]*[a-z0-9])?\.[a-z]{2,}$ ]] || die "CODENVI_HOST '$DOMAIN' is not a valid host name."
[[ -f "$WORK/release.tgz" && -f "$WORK/env_vars" ]] || die "Upload incomplete in $WORK."

# Value of KEY in ENV_VARS (Windows line endings and surrounding quotes tolerated); empty when absent.
env_var() {
  tr -d '\r' < "$WORK/env_vars" | sed -n -E "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*(.*[^[:space:]])?[[:space:]]*$/\1/p" | head -1 \
    | sed -E "s/^([\"'])(.*)\1$/\2/"
}
GIVEN_JWT="$(env_var JWT_SECRET)"
GIVEN_ACCESS="$(env_var CODENVI_ACCESS_KEY)"
for v in GIVEN_JWT GIVEN_ACCESS; do
  [[ "${!v}" != *[\"\\]* ]] || die "${v#GIVEN_} in ENV_VARS must not contain quotes or backslashes."
done
[[ -z "$GIVEN_JWT" || ${#GIVEN_JWT} -ge 32 ]] || die "JWT_SECRET in ENV_VARS must be at least 32 characters (or remove the line: the deploy generates one)."
[[ -z "$GIVEN_ACCESS" || ${#GIVEN_ACCESS} -ge 16 ]] || die "CODENVI_ACCESS_KEY in ENV_VARS must be at least 16 characters (or remove the line: the deploy generates one)."

FIRST_DEPLOY=1
[[ -f "$MARKER" ]] && FIRST_DEPLOY=0

# ---------------------------------------------------------------------------- #
#  1. Tools: install only what is missing. Nothing is upgraded.                #
# ---------------------------------------------------------------------------- #
log "Checking tools"
export DEBIAN_FRONTEND=noninteractive
APT=()
command -v curl  >/dev/null || APT+=(curl)
command -v ss    >/dev/null || APT+=(iproute2)
command -v nginx >/dev/null || APT+=(nginx)
command -v psql  >/dev/null || APT+=(postgresql postgresql-contrib)
command -v certbot >/dev/null || APT+=(certbot)
dpkg -s python3-certbot-nginx >/dev/null 2>&1 || APT+=(python3-certbot-nginx)
if (( ${#APT[@]} )); then
  echo "Installing: ${APT[*]}"
  apt-get update -y -qq
  apt-get install -y -qq "${APT[@]}"
fi
if ! command -v node >/dev/null; then
  echo "Installing Node.js 20"
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y -qq nodejs
fi
NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]')"
(( NODE_MAJOR >= 20 )) || die "Node.js $(node -v) is installed; Codenvi needs 20 or newer. Not upgrading it, because other projects on this VPS use it."
if ! command -v pm2 >/dev/null; then
  echo "Installing PM2"
  npm install -g pm2 >/dev/null
  pm2 startup systemd -u root --hp /root >/dev/null || true
fi
systemctl is-active --quiet postgresql || systemctl enable --now postgresql
systemctl is-active --quiet nginx || systemctl enable --now nginx
ok "node $(node -v), pm2 $(pm2 -v), $(nginx -v 2>&1 | cut -d' ' -f3), $(psql --version | cut -d' ' -f1,3)"

# ---------------------------------------------------------------------------- #
#  2. Preflight: make sure nothing here belongs to another project             #
# ---------------------------------------------------------------------------- #
log "Checking that '$APP_NAME' does not collide with other projects"
pg() { runuser -u postgres -- psql -X -v ON_ERROR_STOP=1 -qtA "$@"; }
ROLE_EXISTS="$(pg -c "SELECT 1 FROM pg_roles WHERE rolname = '$DB_NAME'")"
DB_OWNER="$(pg -c "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = '$DB_NAME'")"
OUR_PID="$(pm2 pid "$APP_NAME" 2>/dev/null | tr -d '[:space:]' || true)"

if (( FIRST_DEPLOY )); then
  [[ ! -e "$APP_DIR" ]] || die "$APP_DIR already exists and was not created by this deploy. Choose another CODENVI_APP_NAME."
  ! pm2 describe "$APP_NAME" >/dev/null 2>&1 || die "A PM2 process named '$APP_NAME' already exists. Choose another CODENVI_APP_NAME."
  [[ ! -e "$VHOST" && ! -e "$VHOST_ENABLED" ]] || die "nginx site '$APP_NAME' already exists. Choose another CODENVI_APP_NAME."
  [[ -z "$ROLE_EXISTS" ]] || die "PostgreSQL role '$DB_NAME' already exists (another project?). Choose another CODENVI_APP_NAME (the database is named after it)."
  [[ -z "$DB_OWNER" ]] || die "Database '$DB_NAME' already exists (another project?). Choose another CODENVI_APP_NAME (the database is named after it)."
else
  MARKED_DB="$(sed -n 's/^database=//p' "$MARKER")"
  [[ "$MARKED_DB" == "$DB_NAME" ]] || die "The database name changed from '$MARKED_DB' to '$DB_NAME' (the app name changed). That would leave the data behind; move it manually first."
  [[ -z "$DB_OWNER" || "$DB_OWNER" == "$DB_NAME" ]] || die "Database '$DB_NAME' is owned by '$DB_OWNER', not by this app."
  if [[ -e "$VHOST" ]] && ! grep -q "$OWN_TAG" "$VHOST"; then die "$VHOST was replaced by something else; not touching it."; fi
fi

LISTENERS="$(ss -ltnpH "sport = :$PORT" 2>/dev/null | grep -o 'pid=[0-9]*' | cut -d= -f2 | sort -u || true)"
for pid in $LISTENERS; do
  [[ -n "$OUR_PID" && "$OUR_PID" != "0" && "$pid" == "$OUR_PID" ]] \
    || die "Port $PORT is used by another process (pid $pid: $(ps -o comm= -p "$pid" 2>/dev/null)). Choose another CODENVI_PORT."
done
# A listener whose process could not be identified still counts, unless it is certainly ours.
if [[ -z "$LISTENERS" ]] && ss -ltnH "sport = :$PORT" 2>/dev/null | grep -q .; then
  [[ -n "$OUR_PID" && "$OUR_PID" != "0" ]] || die "Port $PORT is already in use. Choose another CODENVI_PORT."
fi

if [[ -d /etc/nginx ]]; then
  for f in $(grep -rlE "^[[:space:]]*server_name[^;]*[[:space:]]${DOMAIN//./\\.}([[:space:];]|$)" /etc/nginx/sites-enabled /etc/nginx/conf.d 2>/dev/null || true); do
    [[ "$(basename "$f")" == "$APP_NAME" ]] || die "Host $DOMAIN is already served by $f. Choose another CODENVI_HOST."
  done
fi
ok "No collisions: folder, PM2 process, nginx site, port $PORT, host $DOMAIN, database $DB_NAME"

mkdir -p "$APP_DIR/shared" "$APP_DIR/logs" "$APP_DIR/backups"
chmod 700 "$APP_DIR/shared" "$APP_DIR/backups"
printf 'name=%s\ndatabase=%s\n' "$APP_NAME" "$DB_NAME" > "$MARKER"

# ---------------------------------------------------------------------------- #
#  2b. Keys: generated once on the first deploy, then reused                    #
# ---------------------------------------------------------------------------- #
GEN_FILE="$APP_DIR/shared/generated.env"
stored() { [[ -f "$GEN_FILE" ]] && sed -n "s/^$1=//p" "$GEN_FILE" | head -1 || true; }
random() { node -e "process.stdout.write(require('crypto').randomBytes($1).toString('$2'))"; }
GENERATED_NOW=""
JWT_SECRET="$(stored JWT_SECRET)";     [[ -n "$JWT_SECRET" ]]     || { JWT_SECRET="$(random 48 hex)";        GENERATED_NOW="$GENERATED_NOW JWT_SECRET"; }
DB_PASSWORD="$(stored DB_PASSWORD)";   [[ -n "$DB_PASSWORD" ]]    || { DB_PASSWORD="$(random 24 hex)";       GENERATED_NOW="$GENERATED_NOW DB_PASSWORD"; }
ACCESS_KEY="$(stored CODENVI_ACCESS_KEY)"; [[ -n "$ACCESS_KEY" || -n "$GIVEN_ACCESS" ]] || { ACCESS_KEY="$(random 18 base64url)"; GENERATED_NOW="$GENERATED_NOW CODENVI_ACCESS_KEY"; }
umask 077
printf 'JWT_SECRET=%s\nDB_PASSWORD=%s\n' "$JWT_SECRET" "$DB_PASSWORD" > "$GEN_FILE.tmp"
[[ -z "$ACCESS_KEY" ]] || printf 'CODENVI_ACCESS_KEY=%s\n' "$ACCESS_KEY" >> "$GEN_FILE.tmp"
mv -f "$GEN_FILE.tmp" "$GEN_FILE"; chmod 600 "$GEN_FILE"
umask 022
# Values given in ENV_VARS win over generated ones.
[[ -z "$GIVEN_JWT" ]]    || JWT_SECRET="$GIVEN_JWT"
[[ -z "$GIVEN_ACCESS" ]] || ACCESS_KEY="$GIVEN_ACCESS"
if [[ -n "$GENERATED_NOW" ]]; then ok "Generated on this deploy and stored in $GEN_FILE:$GENERATED_NOW"; else ok "Keys: reusing those stored in $GEN_FILE"; fi

# ---------------------------------------------------------------------------- #
#  3. Database: this app's own role and database only                          #
# ---------------------------------------------------------------------------- #
log "Database '$DB_NAME'"
runuser -u postgres -- psql -X -v ON_ERROR_STOP=1 -q -v name="$DB_NAME" -v pw="$DB_PASSWORD" <<'SQL'
SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'name') AS create_role \gset
\if :create_role
  CREATE ROLE :"name" LOGIN PASSWORD :'pw';
\else
  ALTER ROLE :"name" LOGIN PASSWORD :'pw';
\endif
SELECT NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'name') AS create_db \gset
\if :create_db
  CREATE DATABASE :"name" OWNER :"name";
\endif
REVOKE ALL ON DATABASE :"name" FROM PUBLIC;
SQL
ok "Role and database '$DB_NAME' ready (created if they did not exist)"

# ---------------------------------------------------------------------------- #
#  4. Environment file                                                         #
# ---------------------------------------------------------------------------- #
log "Writing $ENV_FILE"
PW_URL="$(printf '%s' "$DB_PASSWORD" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>process.stdout.write(encodeURIComponent(s)))')"
# Written by the deploy itself, plus the four settings only the deploy needs, are not copied from ENV_VARS.
MANAGED='NODE_ENV|PORT|HOST|CODENVI_PUBLIC_HOST|DATABASE_URL|CODENVI_APP_NAME|CODENVI_HOST|CODENVI_PORT|CODENVI_DATABASE|JWT_SECRET|CODENVI_ACCESS_KEY|DB_PASSWORD'
umask 077
{
  echo "# Written by the Codenvi deploy from the ENV_VARS secret. Changes here are overwritten."
  tr -d '\r' < "$WORK/env_vars" | grep -vE "^[[:space:]]*($MANAGED)[[:space:]]*=" || true
  echo "NODE_ENV=production"
  echo "PORT=$PORT"
  echo "HOST=127.0.0.1"
  echo "CODENVI_PUBLIC_HOST=$DOMAIN"
  echo "JWT_SECRET=\"$JWT_SECRET\""
  echo "CODENVI_ACCESS_KEY=\"$ACCESS_KEY\""
  echo "DATABASE_URL=postgresql://$DB_NAME:$PW_URL@127.0.0.1:5432/$DB_NAME"
} > "$ENV_FILE.tmp"
mv -f "$ENV_FILE.tmp" "$ENV_FILE"
chmod 600 "$ENV_FILE"
umask 022
ok ".env written (mode 600)"

# ---------------------------------------------------------------------------- #
#  5. Unpack, backup, migrations (the running code is not touched yet)         #
# ---------------------------------------------------------------------------- #
STAMP="$(date -u +%Y%m%d%H%M%S)"
STAGE="$APP_DIR/.staging"
log "Deploy $STAMP"
rm -rf "$STAGE" && mkdir -p "$STAGE"
tar -xzf "$WORK/release.tgz" -C "$STAGE"
[[ -f "$STAGE/dist/index.js" && -f "$STAGE/dist/migrate.js" && -d "$STAGE/node_modules" ]] || { rm -rf "$STAGE"; die "Release bundle is incomplete."; }

HAS_TABLES="$(pg -d "$DB_NAME" -c "SELECT count(*) FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog','information_schema')")"
if (( HAS_TABLES > 0 )); then
  BACKUP="$APP_DIR/backups/pre-$STAMP.sql.gz"
  runuser -u postgres -- pg_dump --no-owner "$DB_NAME" | gzip > "$BACKUP"
  chmod 600 "$BACKUP"
  ls -1t "$APP_DIR"/backups/pre-*.sql.gz 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r rm -f
  ok "Backup: $BACKUP"
fi

if ! (cd "$STAGE" && DOTENV_CONFIG_PATH="$ENV_FILE" node dist/migrate.js); then
  rm -rf "$STAGE"
  die "Migrations failed. The running server was not touched."
fi
ok "Migrations applied"

# ---------------------------------------------------------------------------- #
#  6. Replace the code in place and (re)start under PM2                        #
# ---------------------------------------------------------------------------- #
log "Starting $APP_NAME"
# Stop our own process first so node_modules is not replaced under a running server (a few seconds of downtime).
pm2 delete "$APP_NAME" >/dev/null 2>&1 || true
for item in dist drizzle demo node_modules package.json package-lock.json; do
  rm -rf -- "${APP_DIR:?}/$item"
  [[ -e "$STAGE/$item" ]] && mv "$STAGE/$item" "$APP_DIR/$item"
done
rm -rf "$STAGE"
# Folders from the old release-based layout are not used any more.
rm -rf -- "${APP_DIR:?}/releases"; rm -f -- "$APP_DIR/current" "$APP_DIR/current.tmp"

cat > "$APP_DIR/ecosystem.config.cjs" <<EOF
// $OWN_TAG. Written on every deploy.
module.exports = {
  apps: [{
    name: "$APP_NAME",
    script: "dist/index.js",
    cwd: "$APP_DIR",
    exec_mode: "fork",
    instances: 1,
    autorestart: true,
    max_memory_restart: "512M",
    restart_delay: 3000,
    kill_timeout: 10000,
    env: { NODE_ENV: "production", DOTENV_CONFIG_PATH: "$ENV_FILE" },
    out_file: "$APP_DIR/logs/out.log",
    error_file: "$APP_DIR/logs/error.log",
    merge_logs: true,
    time: true,
  }],
};
EOF
pm2 start "$APP_DIR/ecosystem.config.cjs" --update-env >/dev/null
pm2 save >/dev/null

healthy=0
for _ in $(seq 1 30); do
  if curl -fsS --max-time 2 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then healthy=1; break; fi
  sleep 1
done
if (( ! healthy )); then
  pm2 logs "$APP_NAME" --lines 30 --nostream || true
  die "Health check failed; see the logs above. There is no automatic rollback: fix the problem and deploy again."
fi
ok "Healthy: $(curl -fsS "http://127.0.0.1:$PORT/health")"

# ---------------------------------------------------------------------------- #
#  7. nginx site + certificate for DOMAIN only                                 #
# ---------------------------------------------------------------------------- #
log "nginx site $APP_NAME -> 127.0.0.1:$PORT"
RENDERED="$(mktemp)"
cat > "$RENDERED" <<EOF
# $OWN_TAG
# Written by the Codenvi deploy. certbot adds the HTTPS part for this host only.
server {
    listen 80;
    server_name $DOMAIN;

    add_header X-Content-Type-Options "nosniff" always;
    add_header Referrer-Policy "no-referrer" always;
    client_max_body_size 10M;

    access_log /var/log/nginx/$APP_NAME.access.log;
    error_log  /var/log/nginx/$APP_NAME.error.log warn;

    location / {
        proxy_pass http://127.0.0.1:$PORT;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$remote_addr;
        proxy_set_header X-Forwarded-Proto \$scheme;
        # WebSockets stay open for hours.
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_connect_timeout 10s;
        proxy_buffering off;
    }
}
EOF
if [[ -f "$VHOST" ]] && grep -q "server_name $DOMAIN;" "$VHOST" && grep -q "proxy_pass http://127.0.0.1:$PORT;" "$VHOST"; then
  ok "Site unchanged (kept, including its HTTPS part)"
else
  PREV_VHOST=""
  [[ -f "$VHOST" ]] && { PREV_VHOST="$(mktemp)"; cp -a "$VHOST" "$PREV_VHOST"; }
  install -m 644 "$RENDERED" "$VHOST"
  ln -sfn "$VHOST" "$VHOST_ENABLED"
  if nginx -t 2>/dev/null; then
    systemctl reload nginx
    ok "Site written, nginx reloaded"
  else
    nginx -t || true
    if [[ -n "$PREV_VHOST" ]]; then cp -a "$PREV_VHOST" "$VHOST"; else rm -f "$VHOST" "$VHOST_ENABLED"; fi
    die "nginx rejected the new site; it was removed again and no other site was changed. The server itself is running."
  fi
fi
rm -f "$RENDERED"

if [[ -f "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" ]]; then
  if ! grep -q "listen 443" "$VHOST"; then
    certbot install --nginx --cert-name "$DOMAIN" --non-interactive \
      || warn "Could not attach the existing certificate. Run: certbot install --nginx --cert-name $DOMAIN"
  fi
  ok "Certificate for $DOMAIN present (renewed by certbot's own timer/cron)"
else
  # No e-mail address is registered (none is needed); certbot still renews certificates automatically.
  certbot --nginx --non-interactive --agree-tos --register-unsafely-without-email -d "$DOMAIN" --redirect \
    || warn "certbot failed. Does the DNS A record of $DOMAIN point at this VPS, and is port 80 open? Push again once it does."
fi
nginx -t 2>/dev/null && systemctl reload nginx

# ---------------------------------------------------------------------------- #
#  8. Admin command                                                            #
# ---------------------------------------------------------------------------- #
if [[ ! -e "$ADMIN_BIN" ]] || grep -q "$OWN_TAG" "$ADMIN_BIN"; then
  cat > "$ADMIN_BIN" <<EOF
#!/usr/bin/env bash
# $OWN_TAG
# Pairing and emergency commands for the Codenvi server, e.g.: $APP_NAME-admin pair --name "Home PC"
[[ \$EUID -eq 0 ]] || exec sudo "\$0" "\$@"
if [[ "\${1:-}" == "password" ]]; then   # the password to type in the apps (next to the host name $DOMAIN)
  sed -n 's/^CODENVI_ACCESS_KEY=//p' "$ENV_FILE" | head -1 | sed -E 's/^"(.*)"\$/\1/'
  exit 0
fi
cd "$APP_DIR" && DOTENV_CONFIG_PATH="$ENV_FILE" exec node dist/admin.js "\$@"
EOF
  chmod 755 "$ADMIN_BIN"
else
  warn "$ADMIN_BIN belongs to something else; not created. Use: cd $APP_DIR && DOTENV_CONFIG_PATH=$ENV_FILE node dist/admin.js"
fi

log "Deployed $APP_NAME ($STAMP)"
echo "  URL:      https://$DOMAIN"
echo "  Folder:   $APP_DIR"
echo "  Port:     127.0.0.1:$PORT"
echo "  Database: $DB_NAME"
echo "  Logs:     pm2 logs $APP_NAME"
echo "  Admin:    $APP_NAME-admin <pair|installs|revoke|pairing|purge|password>"
echo
echo "  Connect each app (Windows app on every PC, and the Android app) with two fields:"
echo "    Host name: $DOMAIN"
if [[ -n "$GIVEN_ACCESS" ]]; then
  echo "    Password:  the CODENVI_ACCESS_KEY from your ENV_VARS secret"
else
  echo "    Password:  generated for you. Read it on the VPS with:  $APP_NAME-admin password"
fi
exit 0
