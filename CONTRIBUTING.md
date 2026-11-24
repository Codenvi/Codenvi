# Contributing to Codenvi

Thanks for your interest. Codenvi is a small personal-use project, and contributions are welcome. By taking part you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Before you start

- **Bugs and ideas:** open an issue using the templates. Check existing issues first.
- **Security problems:** do not open an issue; follow [SECURITY.md](SECURITY.md).
- **Larger changes:** open an issue to discuss first, so the work is not wasted.

## Repository layout

| Path | What |
|---|---|
| `server/` | Node.js + TypeScript relay server (Express, `ws`, Drizzle, PostgreSQL) |
| `windows/` | WPF desktop app (.NET) |
| `android/` | Jetpack Compose app (Kotlin) |
| `shared/` | `Codenvi.Contracts`, the C# DTOs and crypto used by the Windows app |
| `docs/` | [design](docs/design.md), [protocol](docs/protocol.md), [crypto](docs/crypto.md), [building](docs/building.md), [self-hosting](docs/self-hosting.md), [deployment](docs/deployment.md) |

## Building and testing

Prerequisites and full commands are in [docs/building.md](docs/building.md). In short:

```bash
cd server  && npm ci && npm run typecheck && npm test        # server
cd windows && dotnet test Codenvi.Desktop.sln                # Windows app + shared
cd android && ./gradlew testDebugUnitTest assembleDebug      # Android app
```

The server's database tests need `CODENVI_TEST_PG` (see the building guide). Pull requests run the same checks in GitHub Actions.

## Pull requests

- Keep each pull request focused on one change.
- Add or update tests. Changes to the crypto format must update [docs/crypto.md](docs/crypto.md), the test vectors, and the C# and Kotlin implementations together.
- Changes to the protocol must update [docs/protocol.md](docs/protocol.md) and all three implementations.
- Follow the style of the surrounding code and the `.editorconfig` in each folder.
- **Never commit secrets** (keys, tokens, keystores, `.env` files). A secret scan runs on every push.
- Do not commit built binaries (`Codenvi.exe`, `Codenvi.apk`) in a pull request. The owner's workflow builds them.
- Commit messages: a short imperative summary. The deploy tags `[server]`, `[windows]` and `[android]` only act in the owner's repository; forks only build and test.

## License

By contributing you agree that your contribution is licensed under the [MIT License](LICENSE).
