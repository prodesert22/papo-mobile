# Install

[Leia em português](install.pt-BR.md)

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
