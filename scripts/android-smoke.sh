#!/usr/bin/env bash
set -euo pipefail

mkdir -p artifacts/android-smoke
./gradlew assembleDebug assembleDebugAndroidTest
adb shell screenrecord --time-limit 90 /sdcard/floating-notes-smoke.mp4 > artifacts/android-smoke/recording.log 2>&1 &
recording_pid=$!

collect() {
    adb shell pkill -INT screenrecord || true
    wait "$recording_pid" || true
    adb pull /sdcard/floating-notes-smoke.mp4 artifacts/android-smoke/ || true
    adb pull /sdcard/Android/data/io.github.z3275630ops.floatingnotes.debug/files/evidence artifacts/android-smoke/ || true
    adb shell getprop ro.build.fingerprint > artifacts/android-smoke/device.txt || true
}
trap collect EXIT
./gradlew connectedDebugAndroidTest
