# Papo Mobile

[![CI](https://github.com/prodesert22/papo-mobile/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/prodesert22/papo-mobile/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/prodesert22/papo-mobile?label=release)](https://github.com/prodesert22/papo-mobile/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/prodesert22/papo-mobile/total)](https://github.com/prodesert22/papo-mobile/releases)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue)](LICENSE)
[![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)](#install)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](#build)

[Leia em português](README.pt-BR.md)

Android client for [Papo](https://github.com/prodesert22/papo-backend), a
self-hosted chat service with text, voice and video. Sign in to several servers
and accounts at once, chat in channels and direct messages, and join voice calls
with camera and screen sharing. Everything the app stores on the phone is
encrypted and can be locked behind your fingerprint.

Papo also has a [web client](https://github.com/prodesert22/papo-frontend) and a
[terminal client](https://github.com/prodesert22/papo-tui).

## Features

**Servers and accounts**

- Several servers, each with its own address, and several accounts on the same
  server. Every account keeps its own session, history and media.
- A nickname and a color for each account, to tell them apart.
- Closed servers, which ask for the server password before you sign in.
- Sessions that renew themselves, and a list of connected devices to sign out.

**Privacy on the device**

- Lock the app behind your fingerprint or the phone's screen lock. The key that
  opens the stored data stays in the phone's secure hardware.
- Accounts, sessions, messages and cached media are stored encrypted and are
  left out of device backups.
- Optionally block screenshots and hide conversations in the recent apps list.

**Messages**

- Text channels grouped by category and direct messages, in real time, with
  unread markers.
- Attachments, replies, editing and deleting, reactions, pinned messages and
  mentions.
- Message search on the server, by text and by conversation, author, mention,
  links, attachments and date. A result opens in its conversation, however old
  it is.
- Markdown formatting, link previews, image thumbnails with a full-screen
  viewer, typing indicators and presence.
- Emoji: a searchable picker with every Unicode emoji and the server's own,
  suggestions as you type `:name`, the server's emoji shown inside messages,
  and larger emoji in messages that have nothing else.

**Voice, camera and screen sharing**

- Join a voice channel, mute, switch cameras and choose between the speaker and
  the earpiece.
- Share your camera or your screen, and watch the ones other people share.
- A call continues when you leave the app, with a notification to hang up.

The complete list is in the [0.1.0 release notes](releases/v0.1.0.md).

## Current limitations

- No push notifications: messages arrive only while the app is open.
- No notification inbox, profile editing, user preferences, blocking or server
  administration.
- In calls: no sound from a shared screen, no per-person volume and no Bluetooth
  headset selection.
- Video attachments play only after being downloaded, and GIFs and animated
  emoji show only their first frame.
- The interface is in Portuguese.

## Install

You need Android 11 or newer and a Papo server to connect to.

1. Open the [latest release](https://github.com/prodesert22/papo-mobile/releases/latest)
   and download the APK for your phone:

   | File | For |
   | --- | --- |
   | `papo-mobile-<version>-arm64-v8a.apk` | Almost every phone sold in the last years |
   | `papo-mobile-<version>-armeabi-v7a.apk` | Older 32-bit phones |
   | `papo-mobile-<version>-x86_64.apk` | Emulators and some tablets and Chromebooks |
   | `papo-mobile-<version>-universal.apk` | Any of them; larger |

2. Open the downloaded file. Android asks you to allow installing apps from the
   app you opened it with; allow it and install.
3. Open Papo, type the address of your server and sign in or create an account.

To check a download, put `SHA256SUMS` from the same release next to the APK and
run `sha256sum --check --ignore-missing SHA256SUMS`.

An address that starts with `http://` shows a warning before connecting, because
passwords and messages travel unencrypted. Use it only on a network you trust.

To lock the app, tap the gear at the bottom of the server rail and turn on
**Bloquear com digital**.

## Build

You need:

- A JDK, version 17 or newer, to start Gradle. The build itself uses JDK 21 and
  downloads it when it is not installed.
- The Android SDK. Point `ANDROID_HOME` at it, or create `local.properties` with
  `sdk.dir=/path/to/Android/Sdk`. Gradle downloads the SDK packages it needs
  once the SDK licenses are accepted.

Build the debug APK and install it on a connected device or emulator:

```sh
git clone https://github.com/prodesert22/papo-mobile.git
cd papo-mobile
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Run the checks:

```sh
./gradlew :shared:testAndroidHostTest          # unit tests
./gradlew :androidApp:lintDebug                # Android lint
python3 -m unittest discover -s tools/tests    # tests for tools/release.py
tools/dev/run-device-tests.sh <serial>         # instrumented tests on one device
```

`adb devices` lists the serials. Use the script for the instrumented tests
instead of `connectedAndroidTest`: that task runs on every connected device and
uninstalls the app when it finishes.

On an emulator, a server running on your computer is reachable at
`http://10.0.2.2:<port>`. Without a server, `python3 tools/dev/fake_papo.py 8081`
starts a fake one that covers sign-in, text chat and message search, with the
users `ana` (`Senha@123`) and `bia` (`Senha@456`). It does not replace a real
server and has no voice calls.

A release build is minified and needs a signing key; see
[releases/README.md](releases/README.md) (in Portuguese).

## Project layout

`shared/` is the Kotlin Multiplatform module with almost all the code, including
the Compose interface; `androidApp/` is the Android application. Only the Android
target is built: the iOS targets are declared and have no implementation yet.
The service contract is in [openapi.yml](openapi.yml).

[AGENTS.md](AGENTS.md) describes the layout in detail and the rules for changing
the code.

## Releases

Versions follow `major.minor.patch`, with betas such as `1.0.1-beta.1`. Each
final version has its notes in [releases/](releases/), and
[releases/README.md](releases/README.md) (in Portuguese) explains how a version
is published.

## License

Papo Mobile is licensed under the
[GNU Affero General Public License v3.0](LICENSE).

The bundled Bricolage Grotesque font is licensed under the
[SIL Open Font License](licenses/BricolageGrotesque-OFL.txt). The Unicode emoji
names and keywords come from [emojibase-data](licenses/Emojibase-MIT.txt) (MIT),
built on Unicode CLDR data under the [Unicode License v3](licenses/Unicode-3.0.txt).
The libraries the app depends on keep their own licenses.
