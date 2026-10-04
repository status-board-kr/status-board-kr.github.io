#!/usr/bin/env bash
set -euo pipefail
task_proof="$(pwd)/native-device-proof"
mkdir -p "$task_proof"
# Emulator home process can show its own ANR over an otherwise responsive test Activity.
# Disable only this disposable emulator's launcher, never any user's installed package.
adb shell am force-stop com.google.android.apps.nexuslauncher
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher
adb shell cmd alarm set-timezone Asia/Seoul
adb shell settings put secure location_mode 3
adb shell settings put system font_scale 1.0
adb logcat -c
(while true; do adb emu geo fix 126.7867 35.3016 >/dev/null; sleep 3; done) &
gps_process=$!
trap 'kill "$gps_process" 2>/dev/null || true; adb logcat -d > "$task_proof/logcat.txt"; adb pull /data/local/tmp/native-verification "$task_proof/verification" || true' EXIT
cd native-android
gradle :app:connectedDebugAndroidTest --no-daemon
