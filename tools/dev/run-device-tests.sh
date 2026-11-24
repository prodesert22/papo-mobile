#!/bin/sh
# Roda os testes instrumentados (Keystore e SQLCipher) em UM aparelho, escolhido
# pelo serial. Não usa `connectedAndroidTest` de propósito: aquela tarefa roda em
# todos os aparelhos conectados e desinstala o app ao terminar.
#
# Uso: tools/dev/run-device-tests.sh <serial>     (veja os seriais com `adb devices`)
set -eu
[ $# -eq 1 ] || { echo "uso: $0 <serial>" >&2; exit 2; }
SERIAL=$1
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --console=plain -q
adb -s "$SERIAL" install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk >/dev/null
adb -s "$SERIAL" install -r androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk >/dev/null
OUT=$(adb -s "$SERIAL" shell am instrument -w chat.papo.mobile.test/androidx.test.runner.AndroidJUnitRunner)
echo "$OUT"
adb -s "$SERIAL" uninstall chat.papo.mobile.test >/dev/null 2>&1 || true
echo "$OUT" | grep -q '^OK (' || exit 1
