# Security policy

Codenvi lets an AI agent change files and run commands on your PCs, so security reports are taken seriously.

## Supported versions

Only the latest release (the current `main`) receives security fixes.

## Reporting a vulnerability

**Please do not open a public issue.** Report it privately instead:

1. Open the [Security tab](https://github.com/Codenvi/Codenvi/security) of this repository.
2. Choose **Report a vulnerability** and describe the problem.

Please include:

- the affected part (server, Windows app, Android app) and version;
- steps to reproduce, or a proof of concept;
- what an attacker could do with it.

Do not include real secrets, tokens, encryption keys or your server's host name in the report.

You can expect an acknowledgement within a few days. Fixes are published as a new release, and the reporter is credited unless they prefer otherwise.

## Scope and design

How messages are protected (AES-256-GCM, per-device keys, replay protection, revocation) is described in [docs/crypto.md](docs/crypto.md) and the security model in [README.md](README.md#security-model-in-one-paragraph). Reports that show how a compromised server could read prompts or run a command on a PC are especially welcome.
