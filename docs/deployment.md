# Deployment and release tags

How the owner's repository builds and deploys. Forks build and test but never sign, deploy or push binaries.

## Deploying changes (owner's repository)

Put tags in any commit message of a push to `main`, in any combination:

| Tag | Result |
|---|---|
| `[android]` | tests and builds the app, commits `android/Codenvi.apk` |
| `[windows]` | tests and builds the app, commits `windows/Codenvi.exe` |
| `[server]` | tests the server, then deploys database migrations and code to your VPS |

With several keywords the **server always goes first**: the apps are built only after the server deploy succeeded, and not at all if it failed. So `Release [server] [windows] [android]` in one commit is fine. A push without keywords starts nothing. Everything can also be run manually (Actions → publish → Run workflow, tick the parts you want). Secrets: `VPS_HOST`, `VPS_USER`, `VPS_SSH_PRIVATE_KEY`, `ENV_VARS`, and for the APK `ANDROID_KEYSTORE_B64` + `ANDROID_KEYSTORE_PASSWORD` ([docs/building.md](building.md#android-signing)). Every APK is signed with the same key, so it installs over the previous one and the phone stays paired. Forks build and test but never sign, deploy or push binaries.

Rollback is `git revert` of the commits after the good one, then push with the tag again.

## Defaults chosen for the open questions (design.md §15)

| Q | Default in this code | Change it by |
|---|---|---|
| Q1 Domain | A domain (`CODENVI_HOST` in `ENV_VARS`) with nginx + Let's Encrypt is required. Self-signed setups are supported by the apps through the `pin=` parameter (see self-hosting docs). | Set `CODENVI_PIN` in `ENV_VARS` |
| Q2 Tags | `[server]`, `[windows]` and `[android]` in any commit of the push. | Edit the `if:` lines in the workflows |
| Q3 Binaries | Committed as `windows/Codenvi.exe` and `android/Codenvi.apk`; the Windows workflow fails above 95 MB with a pointer to Git LFS. Each run also uploads the build as a workflow artifact. | Enable the LFS line in `windows/.gitattributes` |
| Q4 Permissions | `acceptEdits` + per-project allowed tools (`Read Edit Write Glob Grep`), `--permission-prompts none`. `bypassPermissions` needs an explicit confirmation. | Project → Settings tab on the PC |
| Q5 Copyright | `Irakli Gzirishvili` in `LICENSE` and the About screens. | Edit those files |
| Q6 App ID | `app.codenvi` | `android/app/build.gradle.kts` (before the first release) |
