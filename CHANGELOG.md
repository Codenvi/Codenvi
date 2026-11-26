# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Android: voice dictation is transcribed offline on the phone with Whisper (whisper.cpp, English, 5-bit models: Base 57 MB or Small 181 MB, downloaded once from huggingface.co and chosen in Settings → Voice transcription). Tap the mic to record, tap again, and the whole recording is turned into text and inserted at the cursor. This replaces handing the recording to the phone's speech engine, which many engines ignore ("Didn't catch that"). 64-bit ARM phones only; others keep the previous behaviour. The Android build now compiles whisper.cpp with the NDK (downloaded at build time).
- Android: the Code changes page lists files as an indented folder tree (single-folder chains merged on one row, folders first, no icons; dots unchanged), and tapping a file opens a new page with exactly what changed in it: a unified diff with line numbers, green added and red removed lines, `+N −M` totals, and a way back to the Code changes page. New control command `project.diff` (an older PC answers "Unknown command"; the phone says to update the PC). The demo PC simulator answers it too.
- Android 13+: voice dictation now records first and transcribes after. Tap the mic to record (nothing is typed meanwhile, and there are no recognizer restarts), tap again to stop; a spinner shows while the recording is transcribed, then the text is inserted at the cursor in one go. Older Android keeps live, continuous dictation.
- Android: reply sound and notifications. A built-in chime plays whenever a reply arrives, on any page. When Codenvi is in the background, a pop-up shows the project name, the session name and a preview of the reply, and tapping it opens that chat. A small silent foreground service keeps the connection open in the background for this; it can be turned off in Settings → Reply sound and notifications. The notification permission (Android 13+) is requested after pairing.
- Android: on the Multimode page the message block (with the session row above it) now sits at the bottom, below the project grid, like on the chat page.
- Android: the Multimode header has the same **Commit**, **Pull** and **Publish** buttons as the chat header, for the selected project, shown by the same rules (changes to commit, commits behind `origin/main`, commits ahead). They leave their markers in the chat of the selected session.
- Android: the Multimode header has a **Code changes** button (just before the chat button) that opens the Code changes page of the selected project.
- Demo data (`server/demo/demo-seed.js`) rewritten to match the current app: a week of work on 3 PCs plus 3 phones (one revoked), 7 projects, 20 sessions and 50 encrypted messages, with jobs in every state (running on two PCs, queued, waiting for the Claude sign-in, failed with an error, cancelled), unseen replies, project script buttons, Markdown replies with tables and code, and commits including a rollback that reverts an earlier commit. `demo-cleanup.js` still removes it all. New `server/demo/demo-pc.js` brings the demo PCs online and answers the phone, so the Code changes page (files per project, including a very long path), the Commit / Pull / Publish buttons and the Memory tab show data too.
- Android: voice dictation in the chat is now continuous. Tap the mic once to start and again to stop; it keeps listening through pauses (the recognizer is restarted after each sentence or silence), so you can talk slowly and think in between. The restarts reuse the same recognizer and are silent (the system's mic sounds are muted while dictating).
- Android: a **Code changes** button in the chat header (just before Multimode) opens a page that lists the project's uncommitted files grouped by folder (the full folder path once, its files below), with a green (new), blue (updated) or red (deleted) file name and dot, and no code shown. The list scrolls both ways for long paths, every second row is slightly darker like a table, and the header shows the project name under the session name. Its header has the same Commit, Pull and Publish buttons and a chat button that returns to the chat. New control command `project.changes`; an older PC answers "Unknown command", which the phone shows as "Update Codenvi on the PC".
- Android: a copy button on each reply copies the reply text; the reply's cost is no longer shown. The chat input field has rounded corners, and the Devices and Sessions lists show a "Running" chip for the device and session that is running.
- Android chat header: a **Pull** button (new control command `project.pull`) that fetches `origin/main` and merges it into the dev branch, shown only when `origin/main` has commits the dev branch lacks (`behindOrigin` in the project status). Commit, Publish and Pull show a spinner until the PC answers.
- Android: the Commit dialog's text is the whole commit message, and Codenvi no longer commits automatically after a job; changes from an unfinished job are kept in the working tree until you commit them.
- Android: the in-chat commit, push and pull blocks show a commit message on one line (cut with "…" if long) and no commit id: the typed message for a commit, the dev branch's newest commit message for a push, the newest pulled commit's message for a pull (the PC's `project.pull` answer now carries it). The push block is green and the pull block blue.
- Run a project script from the phone: a new shell script field on each project's Settings tab on Windows (cmd.exe commands, one per line, run in order in the project folder, so `code .` and `start chrome http://localhost:3000` work as in a terminal). On Android a script (terminal icon) button appears on the project row when a script exists; after a confirmation the PC starts it in the background (for example a localhost dev server), shows its output on the Terminal tab and can stop it with Stop script. The phone never sends script text.
- Android: while a project's script runs on the PC, its terminal button turns into a stop button that stops the script (new control command `project.script.stop`; the project info the PC shares now also says whether the script is running).
- Windows: a **Terminal** tab (Sessions, Commits, Activity, Terminal, Memory, Settings) with the script output and the Stop script button, which moved here from Settings. The Activity and Terminal tabs scroll to the newest line.
- QR-code pairing: "Connect a phone" and "Show encryption key" on Windows also show a QR code; the Android Connect screen and the "Paste a key string" dialog have a Scan QR code button (Google code scanner, no camera permission). Pasting still works.
- Community files: security policy, contributing guide, code of conduct, issue forms and a pull request template.
- Project website and repository links in the About screens (Windows and Android).
- README badges, troubleshooting section and screenshots placeholder.
- Tests now also run on pushes to `main`.

### Fixed
- Android: the unseen-reply badge and dots (Devices, Projects, Sessions) could be missing when a reply finished while the phone was not connected, or in a session the phone had not loaded yet, because they are computed from the messages stored on the phone and those were only pulled when the chat was opened. On every (re)connect the phone now refreshes each project's sessions and pulls the messages of any session that changed, and a reply for an unknown session first reloads the device, project and session lists.
- Windows/Android: the Publish button no longer shows when there is nothing to push. The "ahead" count is now measured against a freshly fetched `origin/main` (and is 0 when the files equal it, such as right after a Pull) instead of the local `main`.
- Android: tapping the dictation button no longer plays the mic on, off, on sounds. The speech service now picks offline or online itself (the offline-only first attempt that failed and restarted the recognizer is gone), a restart is limited to one retry with the phone's default language, and the recognizer is cancelled silently. A tap while it is still starting now stops it instead of starting another round.
- Android: the first tap on the dictation button no longer shows "Speech recognition error (11)"; the app quietly retries while the speech service is starting up.
- Android: the Devices, Projects, project (Sessions, Memory, Commits) and Multimode pages now fill the screen height; the page area no longer double-counts the status bar and no longer shrinks to its content.
- Android: dictation no longer fails with "Speech recognition error (12)" when the offline language pack is missing; it retries online and with the phone's default language, and explains the error if it still fails.

### Changed
- Android: the Devices list no longer prints "Online" or "Offline" under the device name; the green (online) or grey (offline) dot says it.
- Documentation: the README, the website and the docs page no longer say the PC commits the result after each job (changes stay in the working tree until you Commit) or that Codenvi never pushes (Publish pushes); `docs/design.md` and the website now describe offline Whisper dictation, reply sound and notifications, and the Settings sections that exist today.
- Documentation brought in line with the code: `docs/design.md` now lists every control command (script run/stop, commit, pull, publish), the QR pairing, the full schema (`jobs.delivered_at`, indexes, migrations `0002`/`0003`, no `GRANT`s), the demo data folder and the extra workflows; it no longer says Codenvi commits after each job or that a failed health check rolls the server back (it doesn't, in `design.md`, `building.md` and `self-hosting.md`); `codenvi-admin password` is listed.
- Android: the in-chat commit, push and pull blocks show the action's icon (the same Commit, Publish and Pull icons as the chat header) instead of the words "Committed", "Pushed" and "Pulled", followed by the one-line message.
- Windows: the project's Overview tab is gone; its folder, branch and git state block and buttons are now at the top of the Settings tab, and Sessions is the tab shown first.
- Android: the up/down reorder buttons on projects are removed.
- Android: unseen replies are also shown outside Multimode. A white number badge (count of unseen replies) sits before the 3-dot button on each device in Devices and each project in the Projects list, and a white dot on each session in the Sessions tab.
- Android: Multimode unseen-reply dots (white). The session pill of a session with an unseen reply has a dot before its name, the chat icon in the title bar has a dot while the active project has one, and tapping the read-aloud button counts the reply as seen.
- Android: Multimode polish. The session pills and the read-aloud button sit on one line, the project blocks no longer show the status dot in the bottom right, and the message block no longer shows a loading bar: the Send button spins while that session's reply is pending (tap it to stop the job).
- Android: on the Multimode page the message block has a speaker button at its top right that reads the newest reply of the selected session aloud (the chat page's message block does not have it).
- Android: Multimode shows progress and new replies. After you send, the message block is locked with a loading bar (with a Stop button) until that session's reply arrives, and the project block shows a loading bar while it works. A white dot on a project block marks a reply you have not seen in the chat; opening the chat, or sending another message in that session, clears it.
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
