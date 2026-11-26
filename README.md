<h1 align="center">Papo Mobile</h1>

<p align="center">

[![CI](https://github.com/prodesert22/papo-mobile/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/prodesert22/papo-mobile/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/prodesert22/papo-mobile?label=release)](https://github.com/prodesert22/papo-mobile/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/prodesert22/papo-mobile/total)](https://github.com/prodesert22/papo-mobile/releases)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue)](LICENSE)
[![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)](#install)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](#build)

</p>

[Leia em português](README.pt-BR.md)

Android client for [Papo](https://github.com/prodesert22/papo-backend), a
self-hosted chat service with text, voice and video. Sign in to several servers
and accounts at once, chat in channels and direct messages, and join voice calls
with camera and screen sharing. Everything the app stores on the phone is
encrypted and can be locked behind your fingerprint.

Papo also has a [web client](https://github.com/prodesert22/papo-frontend) and a
[terminal client](https://github.com/prodesert22/papo-tui).

## Features

- Several servers and accounts at once, each one isolated from the others.
- Channels and direct messages in real time, with search, replies, reactions,
  mentions, Markdown and emoji.
- Voice calls with camera and screen sharing.
- Everything stored on the phone is encrypted and can be locked behind your
  fingerprint.

The full list and what is still missing are in [docs/features.md](docs/features.md).

## Install

You need Android 11 or newer. Download the APK for your phone from the
[latest release](https://github.com/prodesert22/papo-mobile/releases/latest)
(`arm64-v8a` fits almost every recent phone), open it and allow the
installation. [docs/install.md](docs/install.md) has the details, including how
to check the download.

## Build

```sh
./gradlew :androidApp:assembleDebug
```

Requirements, tests and development tools are in
[docs/development.md](docs/development.md).

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
