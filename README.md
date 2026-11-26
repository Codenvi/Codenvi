<p align="center"><img src="docs/assets/codenvi-icon.png" width="96" alt="Codenvi"></p>

# Codenvi

<p align="center">
  <a href="https://github.com/Codenvi/Codenvi/actions/workflows/tests.yml"><img src="https://github.com/Codenvi/Codenvi/actions/workflows/tests.yml/badge.svg?branch=main" alt="tests"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange" alt="MIT license"></a>
  <img src="https://img.shields.io/badge/platform-Windows%20%7C%20Android%20%7C%20Node.js-informational" alt="platforms">
  <a href="https://codenvi.github.io/Codenvi/"><img src="https://img.shields.io/badge/website-codenvi.github.io-ff8a1f" alt="website"></a>
</p>

> **Disclaimer.** Codenvi is an independent open-source project. It is not affiliated with, endorsed by, or sponsored by Anthropic, PBC. "Claude" and "Claude Code" are trademarks of Anthropic, PBC.
>
> Codenvi runs the official Claude Code CLI that you install and sign in to yourself on each PC. Codenvi never sees or stores your Claude credentials. Your use of Claude Code is governed by Anthropic's terms, and you are responsible for complying with them.
>
> Codenvi is intended for personal use on your own devices and your own server. It lets an AI agent change files and run commands on your PCs. Use it at your own risk and review its changes before merging them.
>
> Codenvi is provided "as is", without warranty of any kind, under the MIT License.

**Website:** <https://codenvi.github.io/Codenvi/> · **Repository:** <https://github.com/Codenvi/Codenvi>

Codenvi lets one person drive Claude Code on several Windows PCs from an Android phone. Each PC has registered project folders. The phone sends a prompt to a project; the PC runs Claude Code on it, commits the result to a development branch (`codenvi-dev`) and sends the answer back. Prompts, replies, project names and commit messages are **end-to-end encrypted** with a key that only the PC and the phone hold - your server only relays ciphertext.

```
Android app ──HTTPS/WSS──▶ your VPS (nginx → Node.js server → PostgreSQL) ◀──HTTPS/WSS── Windows app ──▶ claude -p
```

- **Free and open source (MIT), for personal use.** One owner per installation: you run your own server.
- **No accounts.** You choose one password (a GitHub secret). On the phone and on every PC you type two things once, the server's host name and that password, and each app then holds its own revocable credential.
- **No open ports on your PCs.** Both apps connect outbound.
- **A compromised server can't run commands on your PCs** - it can't produce a valid encrypted prompt.

The full design is in [docs/design.md](docs/design.md).

## What you can do from the phone

- **Prompt and chat:** send prompts to any project on any of your PCs, with sessions per project, model and effort pickers, dictation and read-aloud for replies. **Multimode** sends to several projects from one screen, and unseen replies are marked with a badge or dot.
- **Git from the chat header:** **Commit** the work in the project, **Publish** the `codenvi-dev` branch, and **Pull** when `origin/main` has commits your dev branch doesn't have yet (the Pull button only appears then). Each action asks for confirmation and shows a spinner until the PC answers. Changes from an unfinished job are kept rather than discarded.
- **Run a project script:** set a shell script on the project's Settings tab on Windows (for example a localhost dev server). The phone shows a terminal button, the PC runs the script and shows its output on the **Terminal** tab, and the button turns into a stop button while it runs. The phone never sends script text.
- **Pair by QR code:** **Connect a phone** and **Show encryption key** on Windows show a QR code that the phone can scan.

## Getting started

You need: a small Ubuntu 24.04 VPS, a domain or subdomain pointing at it, Windows 10/11 PCs with [Git for Windows](https://git-scm.com/download/win) and Claude Code installed, and an Android phone (Android 8+).

1. **Server.** Fork the repository, then:
   - add the GitHub secrets `VPS_HOST`, `VPS_USER`, `VPS_SSH_PRIVATE_KEY` and `ENV_VARS`. `ENV_VARS` holds three lines that name your server: `CODENVI_APP_NAME` (also the database name), `CODENVI_HOST` and `CODENVI_PORT`. Nothing about your server is stored in the repository, and the keys (JWT secret, database password, the server's password) are generated for you on the first deploy. Details: [docs/self-hosting.md](docs/self-hosting.md);
   - point a DNS A record at the VPS and push to `main` with `[server]` in the commit message;
   - the two values you type in the apps are the **host name** (`CODENVI_HOST`) and the **password** (`CODENVI_ACCESS_KEY`: put your own in `ENV_VARS`, or read the generated one with `codenvi-admin password` on the VPS).

   The deploy creates its own folder, database, PM2 process and nginx site, and stops before changing anything if one of them would collide with another project on the VPS. Details: [docs/self-hosting.md](docs/self-hosting.md).
2. **PC.** Download [`windows/Codenvi.exe`](windows/Codenvi.exe) from this repository and run it. Windows SmartScreen warns because the exe is unsigned: choose *More info → Run anyway*. Read the disclaimer, enter the **host name** and **password** in *Connect to server* and pick a name.
3. **Claude Code.** Install Claude Code on the PC and sign in to it yourself, in a terminal. Codenvi only checks that `claude` is present and signed in; it never handles your Claude credentials.
4. **Encryption key.** In Codenvi press **Generate key and register this PC**.
5. **Phone.** Get `android/Codenvi.apk` (produced by the `[android]` build, see [docs/building.md](docs/building.md); it appears in the repository after the first build) and install it (allow installing from unknown sources).
6. On the PC press **Connect a phone**, then on the phone tap **Scan QR code from the PC** (or paste the string into the phone). That links the phone to the server and gives it the PC's encryption key in one step. (Or enter the same host name and password on the phone as on the PC, then add the PC's key with **Show encryption key**.)
7. On the PC, **Register project** → pick a folder. Codenvi creates the `codenvi-dev` branch. Send your first prompt from the phone.

**More PCs:** install `Codenvi.exe` on each one and enter the same host name and password. Every PC links on its own and can be revoked on its own. The phone needs each PC's encryption key once: that PC's **Show encryption key** gives a key-only string to paste into the phone. The password only links apps to the server; the encryption keys stay on your PCs and phone, so the server cannot read your messages even though it knows the password.

## What's in the repository

| Path | What |
|---|---|
| `server/` | Node.js + TypeScript + Express relay server with PostgreSQL (Drizzle): `src/`, `tests/`, `drizzle/` migrations, `deploy/deploy.sh` |
| `windows/` | WPF app; `windows/Codenvi.exe` is the built app (pairing, keys, project registration, job engine, git, project scripts and terminal, tray) |
| `android/` | Jetpack Compose app; `android/Codenvi.apk` is the built app (devices, projects, chat, Multimode, commit/publish/pull, project scripts, voice, read aloud) |
| `shared/` | `Codenvi.Contracts`: DTOs, WebSocket envelope, crypto (C#) used by the Windows app |
| `docs/` | [design](docs/design.md), [deployment](docs/deployment.md), [protocol](docs/protocol.md), [crypto + test vectors](docs/crypto.md), [building](docs/building.md), [self-hosting](docs/self-hosting.md) |
| `.github/` | Workflows (GitHub requires this folder at the root) and the gitleaks configuration |

## Screenshots

<!-- Add images to docs/assets/screenshots/ and uncomment:
<p align="center">
  <img src="docs/assets/screenshots/desktop.png" width="48%" alt="Codenvi on Windows">
  <img src="docs/assets/screenshots/phone-chat.png" width="24%" alt="Codenvi on Android">
</p>
-->

## Troubleshooting

- **SmartScreen warns when I run `Codenvi.exe`.** The exe is unsigned. Choose *More info → Run anyway*, or build it yourself ([docs/building.md](docs/building.md)).
- **Jobs wait with "Claude sign-in needed".** Open a terminal on that PC, run `claude` and sign in. Codenvi never handles your Claude credentials.
- **The phone can't connect.** Check the host name and password match the PC's, that the domain has valid HTTPS, and that `https://<host>/` is reachable from the phone ([docs/self-hosting.md](docs/self-hosting.md)).
- **I lost my phone.** Revoke it from the PC (Settings → Revoke) or run `codenvi-admin revoke <id>` on the VPS, then **Regenerate key** on each PC.

## Deployment and release tags

Pushing to `main` with `[server]`, `[windows]` or `[android]` in a commit message builds and deploys that part. The tag table, the defaults chosen for the open design questions and rollback are in [docs/deployment.md](docs/deployment.md).

## Community

[Contributing](CONTRIBUTING.md) · [Security policy](SECURITY.md) · [Code of Conduct](CODE_OF_CONDUCT.md) · [Changelog](CHANGELOG.md)

## Security model in one paragraph

TLS on every hop plus AES-256-GCM per message with a 256-bit device key derived per PC (HKDF). The AAD binds each ciphertext to its device, object, direction and time, so the server can't move a prompt to another project, swap prompt and reply, or replay it under another ID; the desktop also refuses already-seen job/prompt IDs and prompts older than 24 hours. Tokens are DPAPI-protected on the PC and Keystore-wrapped on the phone. Lost phone: revoke it from the PC (Settings → Revoke) or `codenvi-admin revoke <id>` on the VPS, then **Regenerate key** on each PC. See [docs/crypto.md](docs/crypto.md).

## License

[MIT](LICENSE). Third-party packages keep their own licenses (MIT, Apache-2.0 or compatible); notices are in each app's About screen.


