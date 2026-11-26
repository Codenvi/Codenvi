# Codenvi protocol

Normative description of the REST API and WebSocket protocol between the apps and the Codenvi server
(docs/design.md §7). C# types: `shared/Codenvi.Contracts`. TypeScript: `server/src/routes`, `server/src/lib/envelope.ts`. Kotlin types: `android/app/src/main/java/app/codenvi/net/Dtos.kt`.

All JSON uses camelCase. Byte arrays are standard Base64 strings. IDs are lowercase hyphenated UUIDs.
Timestamps ending in `Ms` are Unix milliseconds. Null fields are omitted by the server.

## Headers

Every request and the WebSocket handshake carry:

```
X-Codenvi-Install-Id: <uuid>
X-Codenvi-Client: android/1.4.0+42 (Android 14; Pixel 7)  |  desktop/1.4.0+57 (Windows 11 26100)
Authorization: Bearer <access token>           (everything except /pair, /token/refresh, /health)
```

If the client build is below `app_min_versions.min_build` the server answers **426 Upgrade Required**.
If `X-Codenvi-Install-Id` doesn't match the token, **401**.

Errors use RFC 7807 ProblemDetails (`{type,title,status}`); `title` is safe to show.

## Authentication

| Step | Request | Response |
|---|---|---|
| Pair | `POST /api/v1/pair` `{code, installId, kind, name, appVersion, appBuild, osVersion?, deviceModel?}` | `{accessToken, accessExpiresAtMs, refreshToken, refreshExpiresAtMs, installId}` |
| Refresh | `POST /api/v1/token/refresh` `{installId, refreshToken}` | same as pair |

- Pairing codes: 128-bit, Crockford Base32 (26 chars), single use, 15 minutes, stored as SHA-256. 5 attempts/min/IP; 20 failures in an hour switch pairing off until `codenvi-admin pairing on`.
- Access token: JWT HS256, 15 minutes, claims `iid` (install id) and `kind`.
- Refresh token: 256-bit, 90 days, rotating. **Reusing a rotated refresh token revokes the install**, unless the token issued for it was never used and the rotation is under 24 hours old (a lost response): then a new pair is issued.

## REST endpoints (`/api/v1`)

| Method & path | Who | Body → response |
|---|---|---|
| `POST /pairing-codes` | desktop | `{intendedKind?}` → `{code, host, expiresAtMs}` |
| `GET /installs` | both | → `InstallDto[]` (`isSelf` marks the caller) |
| `PATCH /installs/{id}` | both | `{name}` |
| `DELETE /installs/{id}?confirmSelf=true` | both | revoke; revoking yourself needs `confirmSelf` |
| `POST /devices` | desktop | `{id?, name, keyCheck}` → `DeviceDto` |
| `GET /devices` | both | → `DeviceDto[]` (`online`, `keyVersion`, `keyCheck`) |
| `PATCH /devices/{id}` | both | `{name}` |
| `POST /devices/{id}/key` | desktop (owner) | `{keyVersion, keyCheck}`; cancels pending jobs for the device |
| `DELETE /devices/{id}` | both | soft delete device + projects |
| `GET /devices/{id}/projects` | both | → `ProjectDto[]` |
| `GET /devices/{id}/jobs/active` | both | → `JobDto[]` |
| `PUT /projects/{id}` | desktop (owner) | `{deviceId, encMeta, defaultSessionId?, sortOrder}` |
| `PATCH /projects/{id}` | both | `{encMeta?, sortOrder?, defaultSessionId?}` |
| `DELETE /projects/{id}` | desktop (owner) | hard delete incl. sessions, messages, jobs, commit records |
| `GET /projects/{id}/sessions` | both | → `SessionDto[]` |
| `PUT /sessions/{id}` | both | `{projectId, encMeta}` |
| `PATCH /sessions/{id}` | both | `{encMeta}` |
| `DELETE /sessions/{id}` | both | soft delete session, hard delete its messages |
| `GET /sessions/{id}/messages?afterSeq=&limit=` | both | → `MessageDto[]` ordered by `seq` (limit ≤ 500) |
| `POST /sessions/{id}/messages` | android | `{messageId, ciphertext, clientCreatedAtMs, model?, effort?}` → `{messageId, jobId, seq}` (30/min) |
| `DELETE /sessions/{id}/messages` | both | clear chat |
| `GET /sessions/{id}/jobs` | both | → `JobDto[]` |
| `POST /jobs/{id}/cancel` | android | 202 |
| `GET /projects/{id}/commits` | both | → `CommitDto[]` |
| `PUT /projects/{id}/commits/{commitId}` | desktop (owner) | `{jobId?, gitHash, kind, encMeta, revertedBy?}` |
| `POST /projects/{id}/control` | android | `{requestId, ciphertext, clientCreatedAtMs}` → 202, or 409 if the PC is offline |
| `GET /catalog/models` | both | → `[{alias, label, efforts[], enabled, sortOrder}]` |
| `GET /health`, `GET /api/v1/health` | anyone | `{status, version, database}` |

"desktop (owner)" means the device must belong to the calling install. Ciphertexts are capped at 1 MB (413).
Rate limits: pairing 5/min/IP, prompts and control 30/min/install, everything 300/min/install.

## WebSocket

`wss://<host>/ws?token=<access token>`. Text frames, one envelope per frame:

```json
{ "type": "job.dispatch", "id": "uuid", "ts": 1764000000000, "payload": { } }
```

Close codes: **4001** access token expired → refresh and reconnect; **4003** install revoked → show "This app was disconnected".
The server re-checks expiry and revocation on every received message.

| Type | Direction | Payload |
|---|---|---|
| `welcome` | server→client | `{installId, serverVersion, serverTimeMs}` |
| `hello` | client→server | `{role:"desktop"\|"android", deviceId?}` - a desktop must send its `deviceId` to receive jobs |
| `job.dispatch` | server→desktop | `{job:{id, deviceId, projectId, sessionId, promptMessageId, kind, model?, effort?, queuedAtMs}, prompt:{messageId, ciphertext, clientCreatedAtMs}, recentMessageIds[]}` |
| `job.ack` | desktop→server | `{jobId}` → status `delivered` |
| `job.status` | desktop→server→android | `{jobId, status, startedAtMs?, finishedAtMs?, errorCode?, encError?, sessionId?, projectId?}` |
| `job.progress` | desktop→server→android | `{jobId, sessionId, messageId, ciphertext, clientCreatedAtMs}` (stored as role `progress`, last 200 kept) |
| `job.result` | desktop→server→android | `{jobId, sessionId, messageId, ciphertext, clientCreatedAtMs, commitHash?, final}` (stored as `assistant`) |
| `job.cancel` | server→desktop | `{jobId}` |
| `control.request` | server→desktop | `{requestId, deviceId, projectId, ciphertext, clientCreatedAtMs}` |
| `control.response` | desktop→server→requesting install | same shape |
| `device.presence` | server→android | `{deviceId, online}` |
| `sync.changed` | server→clients | `{entity, id, parentId?}` - entity: `device`, `project`, `session`, `messages`, `commits`, `installs` |
| `ping` / `pong` | both | `{}` every 25 s (WebSocket keep-alive is also on) |
| `error` | server→client | `{code, message}` |

### Job lifecycle

```
queued ──ack──▶ delivered ──▶ running ──▶ succeeded | failed | cancelled
   │                 │
   │                 └─(no running after 2 min)─▶ re-dispatched
   ├──▶ waiting_login (Claude Code not signed in on the PC; held, not failed)
   └──▶ rejected_stale (prompt older than the PC's stale limit)
```

Jobs are persisted before dispatch. On every desktop `hello` the server re-sends all `queued`/`delivered`/`waiting_login` jobs for that device, oldest first. The desktop de-duplicates by job id and prompt message id, and re-reports the final status of jobs it already finished.

### Error codes (`job.status.errorCode`)

`bad_ciphertext`, `replay`, `stale`, `wrong_branch`, `claude_not_found`, `claude_too_old`, `claude_failed`, `timeout`, `project_missing`, `git_failed`, `key_rotated`, `internal`. Details travel encrypted in `encError` (`{summary, detail}`).

## Control commands

Encrypted `{command, projectId, sessionId?, name?, text?, commitHash?}`; result `{ok, error?, text?, status?}`.

| Command | Effect on the PC |
|---|---|
| `session.create` | map a Codenvi session to a new Claude session UUID |
| `session.clearMemory` | clear server chat, new Claude session UUID (old transcript deleted if the PC's setting says so) |
| `project.memory.get` / `project.memory.set` | read / write `CLAUDE.md` |
| `project.rollback` | revert every commit after `commitHash` in one commit |
| `project.status` | `{branch, dirty, lastCommitHash, lastCommitMessage, runningJobId, branchOk, aheadOfMain, behindOrigin}` |
| `project.changes` | list the uncommitted files (`git status --porcelain=v1 -z --untracked-files=all`) as `text` lines `N`, `U` or `D` (new, updated, deleted), a tab, then the path; a rename is the new path `N` plus the old path `D`. At most 3000 lines, then `+`, a tab and the number left out. Paths only, never contents. An older PC answers `Unknown command`. |
| `project.script.run` | start the project's PC-local shell script (cmd.exe commands, one per line, in the project folder) in the background and answer `Script started` at once; errors if none is saved or it is already running. The command carries no script text: the PC only runs what the user saved on the PC. |
| `project.script.stop` | stop the script the PC started for the project (kills its process tree). Answers ok even when none is running. |
| `project.commit` | commit whatever is uncommitted in the dev branch working tree, with `text` as the whole commit message (default `Codenvi: keep changes`); answers the new commit hash in `text`, or `"Nothing to commit"` if the tree was already clean |
| `project.pull` | `git fetch origin <main>` and merge `origin/<main>` into the dev branch; refuses on a dirty tree, no `origin`, or when not on the dev branch; aborts the merge on conflicts. Answers the subject of the newest pulled commit in `text` (docs/design.md §9.5) |
| `project.publish` | merge the dev branch into `main` and `git push origin main`; errors (and aborts the merge, pushing nothing) on conflicts or a failed push (docs/design.md §9.4) |
