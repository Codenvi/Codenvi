# Building Codenvi from source

## Prerequisites

| Part | Needs |
|---|---|
| Server (`server/`) | Node.js 20.12 or newer, npm |
| Windows app (`windows/`) and shared C# library (`shared/`) | Windows 10/11, .NET SDK 10 (`windows/global.json` pins 10.0.100, roll-forward to the latest feature band) |
| Android (`android/`) | JDK 17, Android SDK (compileSdk 34), **NDK 26.3.11579264 and CMake 3.22.1** (Android Studio SDK Manager, or Gradle downloads them when the licences are accepted) and git: the offline voice transcription (whisper.cpp) is compiled from source that CMake downloads at build time, so the first build needs internet and takes a few minutes longer. The Gradle wrapper downloads Gradle 8.7. |
| Server integration tests | PostgreSQL reachable through `CODENVI_TEST_PG` (superuser connection string) |

## Server

All commands run in `server/`.

```bash
cd server
npm ci
npm run typecheck
npm run build                       # tsc -> server/dist/
npm test                            # unit tests; the database tests are skipped without CODENVI_TEST_PG
# with the REST + WebSocket tests (creates and drops a scratch database):
CODENVI_TEST_PG="postgresql://postgres:postgres@localhost:5432/postgres" npm test
```

Run locally (needs a database):

```bash
cp .env.example .env                # set DATABASE_URL and JWT_SECRET (random, 96 hex characters); only local development needs them
npm run build && npm run db:migrate # applies drizzle/ (the same migrator the deploy runs)
npm run dev                         # hot reload, http://localhost:<PORT from .env, default 5080>
npm run admin -- pair --kind desktop   # prints a connection string
```

Locally the port is `PORT` in `server/.env` (default 5080); on the VPS the deploy writes it from `CODENVI_PORT`. The apps only speak HTTPS/WSS, so for local development put a TLS proxy in front (for example `caddy reverse-proxy --from localhost:5443 --to 127.0.0.1:5080`) and trust its local CA on the PC; debug Android builds accept user-installed CAs. Set `CODENVI_PUBLIC_HOST=localhost:5443` in `.env` so connection strings point at the proxy.

### Migrations

Change `server/src/db/schema.ts`, then in `server/`:

```bash
npm run db:generate                          # writes drizzle/NNNN_<name>.sql and its snapshot
npx drizzle-kit generate --custom --name=x   # an empty file for hand-written SQL (data changes)
```

Commit the SQL and `drizzle/meta/`. Never edit a merged migration. Every migration must apply to an empty database (the test suite runs them all from zero), and should stay backward-compatible with the previous server release (expand/contract), because a rollback is a new deploy of older code against a database that has already been migrated (a failed health check does not roll anything back).

## Windows app

```powershell
cd windows
dotnet test Codenvi.Desktop.sln
dotnet publish src/Codenvi.Desktop/Codenvi.Desktop.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o out
```

`out/Codenvi.exe` is a single self-contained file (roughly 70–110 MB); it is the app itself, no installer. Data lives in `%LOCALAPPDATA%\Codenvi`. The `[windows]` workflow builds it the same way and commits it as `windows/Codenvi.exe`.

## Android

```bash
cd android
./gradlew testDebugUnitTest assembleDebug          # app/build/outputs/apk/debug/app-debug.apk (id app.codenvi.debug)
./gradlew assembleRelease                          # debug-signed unless the variables below are set
```

Release signing reads only environment variables:

```bash
export KEYSTORE_PATH=release.jks          # relative to android/
export KEYSTORE_PASSWORD=... KEY_ALIAS=... KEY_PASSWORD=...
```

### Android signing

Every `android/Codenvi.apk` the `[android]` workflow publishes is signed with **your own release key**. Android installs an update over an existing app only if both are signed with the same key, and only then does the app keep its data (its pairing and encryption keys). So the phone stays connected across updates: install the new APK over the old one, never uninstall.

The workflow needs two secrets and fails without them, rather than publishing an APK you would have to reinstall:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_B64` | the keystore file, Base64-encoded |
| `ANDROID_KEYSTORE_PASSWORD` | its password (also used as the key password) |

It also compares the signing certificate with the one in the committed `android/Codenvi.apk` and refuses to publish if they differ.

Create the keystore **once** (PowerShell; `keytool` comes with the JDK). Use the same password for both prompts, or set `ANDROID_KEY_PASSWORD` too:

```powershell
keytool -genkeypair -v -keystore codenvi-release.jks -keyalg RSA -keysize 4096 -validity 36500 -alias codenvi -dname "CN=Codenvi"
[Convert]::ToBase64String([IO.File]::ReadAllBytes("codenvi-release.jks")) | Set-Clipboard   # paste into ANDROID_KEYSTORE_B64
```

**Back up `codenvi-release.jks` and its password outside GitHub** (password manager, encrypted drive). If it is lost, no future APK can update the installed app; you would have to uninstall and pair the phone again. Never commit it (`*.jks` is in `.gitignore`).

Local `assembleDebug` builds are a separate app (`app.codenvi.debug`), so testing them never touches the installed release app.

## Crypto compatibility

C# (Windows app, `shared/`) and Kotlin (Android) implement the same spec ([crypto.md](crypto.md)). Both test suites check the same vectors, and the server tests check the Base32 vector for pairing codes; change the spec, the vectors and both implementations together.
