# Instructions for agents

This file defines how to work in this repository. Read it before changing files.

## Project

Papo Mobile is the Android client for Papo, a self-hosted chat service. The service contract is in `openapi.yml`, a copy of the backend's file. `README.md` is a short overview, with a Portuguese version in `README.pt-BR.md`. The documentation lives in `docs/`: `features.md` lists what the app does and its current limitations, `install.md` covers installation and `development.md` building, checks and the project layout, each with a `.pt-BR.md` version. `releases/README.md` describes versions and the release process. The code is licensed under the AGPL-3.0 (`LICENSE`).

The app is written in Kotlin with Kotlin Multiplatform and Compose Multiplatform. Only the Android target is built; the iOS targets are declared so that shared code compiles against the common standard library, and they have no implementation yet. The main dependencies are Ktor (HTTP and WebSocket), kotlinx.serialization, SQLDelight over SQLCipher, AndroidX Biometric, libwebrtc (`stream-webrtc-android`) and Firebase Cloud Messaging for push. Versions are in `gradle/libs.versions.toml`.

There are host unit tests that run on the JVM with a mocked HTTP engine and an in-memory database, and instrumented tests that need a device or emulator. A screen that opens is not proof that an integration works: check the flow against a server.

`tools/release.py` validates versions and builds release notes. `tools/dev/` holds development tools only: `fake_papo.py` is a fake backend written from the OpenAPI file, `ws_watch.py` observes WebSocket events as another user, `ui.py` drives the app through adb, `run-device-tests.sh` runs the instrumented tests on one device, `setup-release-signing.sh` prepares release signing and `emoji_catalog.py` regenerates the Unicode emoji catalog the app bundles from a pinned `emojibase-data` release (run it with `python3 -I`). Distinguish what was observed on a real backend from what was only exercised against the fake one. Build output stays in `build/`; test account data, cookies, `local.properties`, `keystore.properties`, keystores and `androidApp/google-services.json` (the Firebase client configuration, written by CI from the `GOOGLE_SERVICES_JSON` secret) must not enter commits.

## Working rules

1. Read `docs/features.md` and the relevant parts of `openapi.yml` and `releases/README.md` before proposing or implementing a change.
2. Keep each change limited to the work in progress. Do not mix in future features or unrelated refactoring.
3. Do not invent endpoints, fields, events, permissions or synchronization guarantees. When the specification does not define a behavior, record the gap and choose a solution that does not promise support the backend lacks. Changes to `openapi.yml` require confirmation of the backend's behavior.
4. Treat each account as an isolated context. An account is the pair (server address, user). Cookies, session state, the database, the media cache and the WebSocket of one account are never shared with another, including two accounts on the same server. The same UUID on different servers names different entities.
5. Never write passwords, cookies or key material to logs, versioned files or error messages. Redact secrets from diagnostic output. The session cookie lives only in the encrypted registry database.
6. Treat all remote content as untrusted text. Messages, names and topics are rendered as text, never as markup that can run or navigate on its own. Message links may only use `http` and `https`. Attachments the server marks as sensitive stay hidden until the user asks to see them.
7. Give every coroutine scope, HTTP client, database driver and WebRTC object one owner that closes it. Rethrow `CancellationException`; do not swallow it in a generic `catch`. Cover failure, cancellation and connection shutdown, and wipe key material from memory when the app locks.
8. Everything the app stores on the device stays encrypted: structured data in SQLCipher databases and media in the encrypted cache, with keys derived from the master key held by the Android Keystore. Do not add plaintext files, plaintext preferences holding user data, or an unencrypted disk cache from a library. The one exception is the YouTube player, a WebView: while it is open, Chromium keeps its profile (cookies, storage, cache) in plaintext in the app's data directory. It applies only to that player, which loads only after the user taps play, with the HTTP cache off, no third-party cookies, no JavaScript bridge and no file or content access, and its data is deleted when the player closes and when the app locks. Chromium keeps a few empty databases named after the YouTube origin open until the process ends; they hold no content and are removed the next time the app starts. Do not reuse the WebView for anything else.
9. Platform features go behind an interface in `commonMain` with the implementation in `androidMain`. Do not use Android or Java APIs in `commonMain`; the build fails if you do, because the iOS targets are declared.
10. Text shown to the user lives in `shared/src/commonMain/composeResources/values/strings.xml`, in Portuguese. Do not hard-code interface text in Kotlin. Write code comments in the language already used in the file you are editing.
11. Keep documentation and release notes consistent with what is actually implemented. Do not describe a feature as available before it works. Keep the READMEs short: an overview and links into `docs/`; details go in `docs/`. The feature list and the limitations live in `docs/features.md`. Each English document and its `.pt-BR.md` version say the same thing: change both in the same commit.
12. Prefer small, reviewable changes. Do not declare a change finished without meeting its acceptance criteria and recording evidence.
13. Use existing libraries for new functionality. Before writing your own implementation, check whether the project's dependencies already provide it; if not, look for a library that supports Kotlin Multiplatform, or Android behind a platform interface. Weigh maintenance, license, documentation and integration cost, and check the current version instead of assuming one. Write it from scratch only when no library fits, and record the technical reason.
14. Write every commit message in English, title and body. Pull request titles and descriptions follow the format of the previous pull requests and carry no tool attribution footer (such as "Generated with Claude Code") and no link to an agent session: the session link exposes the maintainer's private work.
15. Respect the server's rate limits. `/auth/*` endpoints, including `whoami` and `refresh`, are limited per address, so do not poll them. Voice signaling is limited per user and each ICE candidate counts, which is why `CallController` paces them. Do not retry a mutation automatically after a 429.
16. Keep the session rules the integration confirmed. The session is carried only by the `Auth` cookie; the client refreshes it once on a 401 and repeats the request. The server validates the token only at the WebSocket handshake, so an open socket is not proof of a valid session: revalidate through REST. The server never announces that someone stopped typing, so the indicator expires on the client. Keep TLS verification on; `http://` servers are accepted only after the explicit warning shown when adding the server.
17. Keep the call contract the integration confirmed. The client creates every offer and the server only answers. The first offer carries all receive-only lines in order (video, microphones, shared-screen audio). Camera and screen sharing are announced before the offer that publishes them, one per offer. Video is forwarded only after a subscription, and the server places it in the first free slot.
18. Record each new feature, behavior change and fix to an already released version in `releases/Unreleased.md`, in the same change. Describe the effect for the user, in English. Do not record as separate fixes the bugs found and fixed in a feature still in development, before its first release. Changes that do not alter the app for its users are not recorded either: this file, CI/CD workflows, development tools, tests, build configuration and contributor documentation.
19. When preparing a final release, move every entry into the full notes in `releases/vMAJOR.MINOR.PATCH.md`, update `papo.version` in `gradle.properties` and empty `releases/Unreleased.md` in the same commit, keeping the file with zero bytes for the next cycle. The notes must cover every change delivered since the previous version. Beta releases have no notes file: they are published with the current content of `releases/Unreleased.md`.
20. Create release tags with a message, because tag signing is enabled for this repository's maintainer and a bare `git tag` stops to ask for one: `git tag -a v1.0.1 -m "Papo Mobile 1.0.1"`. The tag must be `v` followed by `papo.version`.

## Build and validation

The build uses the Gradle wrapper with JDK 21, selected by `gradle/gradle-daemon-jvm.properties`, and the Android SDK from `ANDROID_HOME` or `local.properties`. Do not state that a validation ran without evidence. The commands are:

```sh
./gradlew :shared:testAndroidHostTest      # unit tests
./gradlew :androidApp:lintDebug            # Android lint
./gradlew :androidApp:assembleDebug        # debug APK
python3 -m unittest discover -s tools/tests   # tests for tools/release.py and the fake backend
tools/dev/run-device-tests.sh <serial>     # instrumented tests on one device
```

Run the instrumented tests through `tools/dev/run-device-tests.sh` with the serial of one device. Do not use `connectedAndroidTest`: it runs on every connected device and uninstalls the app when it finishes.

Run the tests that match your changes before saying they are validated. While working, run the test classes you touched (`./gradlew :shared:testAndroidHostTest --tests 'chat.papo.mobile.ChatStore*'`) and compile. Run the three Gradle commands together once, before handing the change over; CI runs everything again on the pull request. For changes to the backend integration, include failure cases and validate against a development server when one is available. Do not put real credentials in tests or fixtures.

When you change a screen, check it once the change is done, on an emulator or a device, in the light and dark themes and with the keyboard open where the screen has a text field. Unit tests do not render Compose. Drive each flow with one shell script of `tools/dev/ui.py` calls and review the screenshots at the end, instead of one command per tap. For calls, check that joining, the camera, screen sharing and leaving still work with a second participant; the server only announces a camera or a shared screen once the media reaches it.

The release build is minified with R8. When you add a library that relies on reflection or native code, or change `proguard-rules.pro`, build the release APK with `./gradlew :androidApp:assembleRelease -Ppapo.abiSplits=true`, install it and exercise sign-in, chat and a call. A release build that compiles is not evidence that it runs. Signing needs `keystore.properties` locally or the signing secrets in CI.

Check workflow changes with `actionlint`. Third-party actions are pinned to a commit SHA with the version in a comment; keep it that way.

## Layout

`shared/` is the multiplatform module and holds almost all the code; `androidApp/` is the Android application with the manifest, the activity and the instrumented tests.

Inside `shared/src/commonMain/kotlin/chat/papo/mobile/`:

- `core/` — crypto primitives, key derivation, dispatchers and time formatting.
- `security/` — the master key contract and the app lock.
- `account/` — servers, accounts and the per-account session.
- `data/api/`, `data/ws/`, `data/db/` — REST, WebSocket events and database access.
- `chat/` — the conversation store, models and Markdown.
- `media/` — the encrypted media cache.
- `voice/` — the call controller and the engine contract.
- `ui/` — screens, components, theme and navigation.

`shared/src/commonMain/sqldelight/` holds the schemas: `registry/` for accounts and `account/` for each account's data. `shared/src/androidMain/.../platform/` holds the Android implementations: Keystore, SQLCipher, WebRTC, the call service and system requests.

Create modules and directories only when there is code that uses them.

## Tests and CI maintenance

- Put tests that need no platform class in `shared/src/commonTest`; tests that need the JDBC driver, the JCA crypto or files go in `shared/src/androidHostTest`. Shared helpers live in `TestResources.kt`.
- The mocked HTTP engine answers on a real thread. Wait for a postcondition, such as a state value, instead of advancing virtual time and assuming the request finished.
- Instrumented tests must not touch the installed app's data. Use the separate vault name and the test database names, and clean up before and after each test.
- Do not treat retrying until it passes, a blanket timeout increase or removing assertions as a fix for an unstable test.
- The CI jobs `quality`, `unit` and `device` are all required, in pull requests and before a release is published. An exclusion needs an explicit reason.
- Keep `tools/release.py` and the Gradle build agreeing on the version code: the release workflow compares them and fails on a mismatch.
