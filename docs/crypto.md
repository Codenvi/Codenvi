# Codenvi end-to-end encryption - specification and test vectors

This file is normative for both implementations:

- C#: `shared/Codenvi.Contracts/Crypto/*` (used by the desktop app and the server tests)
- Kotlin: `android/app/src/main/java/app/codenvi/crypto/*`

Both test suites check the vectors below (`CryptoVectorTests.cs`, `CryptoVectorTest.kt`).
The vectors were generated with an independent Python implementation (`cryptography` package).

## 1. Crockford Base32

- Alphabet: `0123456789ABCDEFGHJKMNPQRSTVWXYZ`
- Bits are read MSB-first; the last symbol is padded with zero bits.
- Decoding ignores `-` and whitespace, is case-insensitive, maps `I`/`L` → `1` and `O` → `0`, and rejects `U` and other characters. Non-zero padding bits are rejected.

Vector: bytes `00 01 02 … 0f` (16 bytes) → `000G40R40M30E209185GR38E1W`

## 2. Device key

- 32 random bytes.
- Display = Base32(key) (52 symbols) + Base32 of the first 20 bits of SHA-256(key) (4 symbols) = 56 symbols, grouped by 4 with `-`.
- Connection strings carry the same 56 symbols without hyphens.

Vector: key = `00 01 02 … 1f` (32 bytes)

```
000G-40R4-0M30-E209-185G-R38E-1W81-24GK-2GAH-C5RR-34D1-P70X-3RFG-CC6W
```

## 3. Key check value (KCV)

`KCV = HMAC-SHA256(key, ASCII "codenvi-kcv-v1")[0..8]`

Vector (same key): `868d11c77051b2cb`

## 4. Encryption key derivation

`encKey = HKDF-SHA256(IKM = deviceKey, salt = UTF-8(deviceId), info = ASCII "codenvi-v1-enc", L = 32)`

`deviceId` is the lowercase, hyphenated UUID string (`3f2504e0-4f89-41d3-9a0c-cc5d7e0c3b6a`).

Vector: `cc52d91b845455535919bafcddced1c60c49aa235b37fc4c3a9fa4ded3d736ec`

## 5. Additional authenticated data

```
"v1|" + deviceId + "|" + objectType + "|" + objectId + "|" + direction + "|" + createdAtUnixMs
```

UUIDs are lowercase and hyphenated, the timestamp is a decimal integer.

| objectType | objectId | direction | createdAt |
|---|---|---|---|
| `message` (user prompt) | message id | `p2d` | client time of the message |
| `message` (assistant reply) | message id | `d2p` | client time of the message |
| `progress` | progress message id | `d2p` | client time |
| `control` | request id | `p2d` | client time |
| `control.response` | request id | `d2p` | client time |
| `job.error` | job id | `d2p` | `0` |
| `project.meta` | project id | `meta` | `0` |
| `session.meta` | session id | `meta` | `0` |
| `commit.meta` | commit row id | `meta` | `0` |

Metadata objects use direction `meta` and time `0` because either side may rewrite them (for example a rename).

## 6. Wire format

```
version (1 byte, 0x01) | keyVersion (2 bytes, big-endian) | nonce (12) | ciphertext | tag (16)
```

AES-256-GCM, random 12-byte nonce, 128-bit tag. Stored as `bytea` and sent as standard Base64 (with padding).

### Full vector

| Input | Value |
|---|---|
| key | `00 01 … 1f` |
| keyVersion | `1` |
| deviceId | `3f2504e0-4f89-41d3-9a0c-cc5d7e0c3b6a` |
| AAD | `v1\|3f2504e0-4f89-41d3-9a0c-cc5d7e0c3b6a\|message\|9b2f6c1e-1a2b-4c3d-8e9f-0a1b2c3d4e5f\|p2d\|1764000000000` |
| nonce | `00 01 … 0b` |
| plaintext | `{"text":"hello"}` (UTF-8) |

Output (hex):

```
010001000102030405060708090a0b51028004c212aa8c6e9925fe8ed0dab0b5200523bd0224f6cfc84459c05e00ae
```

Output (Base64):

```
AQABAAECAwQFBgcICQoLUQKABMISqoxumSX+jtDasLUgBSO9AiT2z8hEWcBeAK4=
```

## 7. Plaintext shapes

Plaintext is UTF-8 JSON with camelCase names. Unknown fields must be ignored.

| Object | JSON |
|---|---|
| Prompt | `{"text","projectId","sessionId","model","effort"}` - the desktop rejects the job if `projectId`/`sessionId`/`model`/`effort` differ from the job row |
| Reply | `{"text","costUsd","durationMs","commitHash","filesChanged","part","parts"}` |
| Progress | `{"line"}` |
| Project meta | `{"name","devBranch"}` |
| Session meta | `{"name"}` |
| Commit meta | `{"message","filesChanged","insertions","deletions"}` |
| Job error | `{"summary","detail"}` |
| Control | `{"command","projectId","sessionId?","name?","text?","commitHash?"}` |
| Control result | `{"ok","error?","text?","status?"}` |

## 8. Replay protection

The desktop keeps the last 10,000 executed job IDs **and prompt message IDs** in SQLite and refuses either if it was seen before. A prompt whose authenticated `createdAt` is older than the stale limit (24 h by default) is reported as `rejected_stale`. Control requests are checked the same way by request ID.
