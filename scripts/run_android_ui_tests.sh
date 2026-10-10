#!/usr/bin/env bash
# Preserve the test result and capture native crashes before the emulator closes.
set -euo pipefail

adb logcat -c
status=0
./gradlew connectedDebugAndroidTest --no-daemon --build-cache --stacktrace --console=plain 2>&1 | tee ui-tests.log || status=$?
adb logcat -d -v threadtime > ui-logcat.log 2>&1 || true
exit "$status"
