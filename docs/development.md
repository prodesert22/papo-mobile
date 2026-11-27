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

Push notifications with the app closed need `androidApp/google-services.json`, the
client configuration of the app's Firebase project. The file is not in the
repository (it is git-ignored). Without it the build works and the app runs with push
off; with it Gradle applies the `google-services` plugin by itself. CI writes it from
the `GOOGLE_SERVICES_JSON` secret when that is set. The server must have push turned
on too, and the fake backend only keeps the registration (`/users/me/push-device`)
without sending any push.

## Checks

```sh
./gradlew :shared:testAndroidHostTest          # unit tests
./gradlew :androidApp:lintDebug                # Android lint
python3 -m unittest discover -s tools/tests    # tests for tools/release.py and the fake backend
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
server and has no voice calls. With `FAKE_PERMISSIONS=demo` it also has roles, channel
permissions and an owner (`dona`, `Senha@000`); the docstring of `fake_papo.py`
describes the scenario and how to load your own from a JSON file. `FAKE_VIDEO_DIR`
points it at a folder of videos to seed (served with `Range`), `FAKE_VIDEO_KBPS`
throttles them and `FAKE_VIDEO_401_ONCE=N` answers 401 to the N-th video request.
A message with a `https://videos.example.com/...` link gets a video embed about a second
later (`GET /embeds/{id}` and the `/embeds/{id}/video` relay, which serves a seeded video
with `Range`), and `FAKE_RELAY_502=1` makes the relay answer 502. `FAKE_AUDIO_DIR` seeds the audio files
of a folder in the `audio` channel, `FAKE_UPLOAD_KBPS` throttles uploads, and
`FAKE_YOUTUBE_ID` sets the id of the seeded YouTube embed (an invented one by default, which
YouTube answers as unavailable). The `embeds` and `galeria` channels hold link previews, GIFs,
moderation states and messages with several images and videos.

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
