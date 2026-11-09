#!/usr/bin/env bash
set -euo pipefail

mkdir -p artifacts/android-smoke
adb shell wm size 720x1280
adb shell wm density 320
gradle_flags=(--no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process)
./gradlew "${gradle_flags[@]}" assembleDebug assembleDebugAndroidTest
adb shell screenrecord --size 540x960 --bit-rate 1000000 --time-limit 180 /sdcard/floating-notes-smoke.mp4 > artifacts/android-smoke/recording.log 2>&1 &
recording_pid=$!
adb logcat -v threadtime > artifacts/android-smoke/logcat.txt 2>&1 &
logcat_pid=$!

collect() {
    kill "$logcat_pid" 2>/dev/null || true
    adb shell pkill -INT screenrecord || true
    wait "$recording_pid" || true
    adb pull /sdcard/floating-notes-smoke.mp4 artifacts/android-smoke/ || true
    adb pull /sdcard/Android/data/io.github.z3275630ops.floatingnotes.debug/files/evidence artifacts/android-smoke/ || true
    adb shell getprop ro.build.fingerprint > artifacts/android-smoke/device.txt || true
}
trap collect EXIT
./gradlew "${gradle_flags[@]}" connectedDebugAndroidTest
