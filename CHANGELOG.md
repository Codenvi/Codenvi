# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- QR-code pairing: "Connect a phone" and "Show encryption key" on Windows also show a QR code; the Android Connect screen and the "Paste a key string" dialog have a Scan QR code button (Google code scanner, no camera permission). Pasting still works.
- Community files: security policy, contributing guide, code of conduct, issue forms and a pull request template.
- Project website and repository links in the About screens (Windows and Android).
- README badges, troubleshooting section and screenshots placeholder.
- Tests now also run on pushes to `main`.

### Fixed
- Android: the first tap on the dictation button no longer shows "Speech recognition error (11)"; the app quietly retries while the speech service is starting up.
- Android: the Devices, Projects, project (Sessions, Memory, Commits) and Multimode pages now fill the screen height; the page area no longer double-counts the status bar and no longer shrinks to its content.
- Android: dictation no longer fails with "Speech recognition error (12)" when the offline language pack is missing; it retries online and with the phone's default language, and explains the error if it still fails.

### Changed
- Android: Multimode polish. The session pills and the read-aloud button sit on one line, the project blocks no longer show the status dot in the bottom right, and the message block no longer shows a loading bar: the Send button spins while that session's reply is pending (tap it to stop the job).
- Android: on the Multimode page the message block has a speaker button at its top right that reads the newest reply of the selected session aloud (the chat page's message block does not have it).
- Android: Multimode shows progress and new replies. After you send, the message block is locked with a loading bar (with a Stop button) until that session's reply arrives, and the project block shows a loading bar while it works. A red dot on a project block marks a reply you have not seen in the chat; opening the chat, or sending another message in that session, clears it.
- Android: the chat message field has an X inside it that clears the text. Multimode has a new grid icon, used in the bottom tab bar and in the chat title bar.
- Website: new hero slogan ("One phone - All your projects - Every PC") and description.
- Windows: Settings > Paired installs: "Revoke selected" is a plain button (no coloured border) and the duplicate "Connect a phone" button is removed (it is in the header).
- Android: Multimode no longer shows the project's recent replies. Choose a project in the grid and one of its sessions in the row above the input, then send. A chat icon at the right of the title bar opens that session's chat. Every chat page has a Multimode icon in the same place that opens Multimode with that chat's project and session preselected.
- Android: the title-bar icons on the project page (new session, reload, save) use the same colour as the icons on the Devices page.
- Android: the read-aloud button on replies is a speaker icon, muted by default and orange while the reply is being read (tap again to stop).
- Android: read aloud now defaults to speed 1.0 and the voice `en-us-x-iom-local` (until you pick others in Settings). If that voice is not installed on the phone, the system default voice is used.
- Android: Settings > Speech input uses a language drop-down (Phone default, English, German, French, Georgian and others) instead of a text field; the choice is saved as soon as you pick it.
- Android: Settings > Read aloud has a play button next to the voice picker that speaks a sample sentence with the selected voice and speed (tap again to stop).
- Android: the Multimode project list is a 3-column grid of blocks (project name over device name); the selected block is orange with white text.
- Android: the dictation button in the chat is a plain dot, muted when idle and a pulsing red while recording.
- Android: on the project Memory tab, Reload (circle arrow) and Save (disk) are icon buttons at the right end of the title bar, and the CLAUDE.md field has a slightly darker background.
- Android: "New session" is now a + button at the right end of the project page title bar, shown only on the Sessions tab.
- Android: the model and effort buttons in the chat show their value directly (no "Model:" or "Effort:" prefix). Models show their versions (Sonnet 5.5, Opus 5.5, Haiku 5.5, Fable 5.2) and "Default" reads "Default (PC setting)". The model names come from a new server migration (`0003_model_labels`), so redeploy the server with `[server]`.
- Android: messages now appear as dark pop-ups at the top of the app with a border and text colour for their type (success, info, warning, error); tap to dismiss.
- Windows: Settings and About now open inside the main window (with a Back button) instead of separate windows; vertical scroll bars are hidden everywhere (scrolling still works).
- The project's owner name and repository URL are filled in (About screens, license notice, build metadata).
- The desktop project button "Change folder" is now labelled "Relocate".
- `Project.md` moved to `docs/design.md`; deployment tags and the defaults table moved to `docs/deployment.md`.

## [1.0.1]

### Changed
- Windows app rebuilt (`windows/Codenvi.exe` 1.0.1).

## [1.0.0]

### Added
- Initial release: relay server, Windows app and Android app with end-to-end encrypted prompts and replies.
