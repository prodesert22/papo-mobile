# Development

[Leia em português](development.pt-BR.md)

## Requirements

- A JDK, version 17 or newer, to start Gradle. The build itself uses JDK 21 and
  downloads it when it is not installed.
- The Android SDK. Point `ANDROID_HOME` at it, or create `local.properties` with
  `sdk.dir=/path/to/Android/Sdk`. Gradle downloads the SDK packages it needs
  once the SDK licenses are accepted.

## Build and install

```sh
git clone https://github.com/prodesert22/papo-mobile.git
cd papo-mobile
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

## Checks

```sh
./gradlew :shared:testAndroidHostTest          # unit tests
./gradlew :androidApp:lintDebug                # Android lint
python3 -m unittest discover -s tools/tests    # tests for tools/release.py
tools/dev/run-device-tests.sh <serial>         # instrumented tests on one device
```

`adb devices` lists the serials. Use the script for the instrumented tests
instead of `connectedAndroidTest`: that task runs on every connected device and
uninstalls the app when it finishes.

## Servers for testing

On an emulator, a server running on your computer is reachable at
`http://10.0.2.2:<port>`. Without a server, `python3 tools/dev/fake_papo.py 8081`
starts a fake one that covers sign-in, text chat and message search, with the
users `ana` (`Senha@123`) and `bia` (`Senha@456`). It does not replace a real
server and has no voice calls.

## Release builds

A release build is minified and needs a signing key; see
[releases/README.md](../releases/README.md) (in Portuguese).

## Project layout

`shared/` is the Kotlin Multiplatform module with almost all the code, including
the Compose interface; `androidApp/` is the Android application. Only the Android
target is built: the iOS targets are declared and have no implementation yet.
The service contract is in [openapi.yml](../openapi.yml).

[AGENTS.md](../AGENTS.md) describes the layout in detail and the rules for
changing the code.
