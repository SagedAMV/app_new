#!/usr/bin/env bash
# Bound the whole test pipeline and diagnostics; never disguise a timeout as success.
set -euo pipefail

adb_timeout="${ANDROID_UI_ADB_TIMEOUT_SECONDS:-20}"
test_timeout="${ANDROID_UI_TEST_TIMEOUT_SECONDS:-480}"
for duration in "$adb_timeout" "$test_timeout"; do
  if [[ ! "$duration" =~ ^[1-9][0-9]*$ ]]; then
    echo "::error title=Android UI configuration::Timeouts must be positive integer seconds"
    exit 2
  fi
done

echo "::notice title=Android UI phase::Clearing device log (limit ${adb_timeout}s)"
timeout --kill-after=5s "${adb_timeout}s" adb logcat -c || echo "::warning title=Android UI diagnostics::Could not clear the device log within its deadline"

echo "::notice title=Android UI phase::Starting device tests (limit ${test_timeout}s; per-test limit 30s)"
status=0
# Bound bash AND tee, not just Gradle: a surviving child must not keep the pipe open.
timeout --kill-after=15s "${test_timeout}s" bash -o pipefail -c './gradlew connectedDebugAndroidTest --no-daemon --build-cache --stacktrace --console=plain -Pandroid.testInstrumentationRunnerArguments.timeout_msec=30000 2>&1 | tee ui-tests.log' || status=$?
echo "::notice title=Android UI phase::Device test command finished with status ${status}"
if [[ "$status" == 124 || "$status" == 137 ]]; then
  echo "::error title=Android UI timeout::Device tests exceeded their deadline; Galaxy is NOT device-verified"
fi

if [[ "$status" == 0 ]]; then
  apk="app/build/outputs/apk/debug/app-debug.apk"
  echo "::notice title=Android APK smoke test::Installing the exact distributable APK on the running emulator"
  test -s "$apk"
  adb install -r "$apk"
  adb shell am force-stop com.unihub.app
  adb shell monkey -p com.unihub.app -c android.intent.category.LAUNCHER 1
  sleep 8
  if ! adb shell pidof com.unihub.app >/dev/null; then
    echo "::error title=Android APK smoke test::The installed test APK did not remain running after launch"
    adb logcat -d -t 500
    exit 1
  fi
  sha256sum "$apk"
  echo "::notice title=Android APK smoke test::Exact APK installed and launched successfully"
fi

if [[ "$status" != 0 && "${GITHUB_ACTIONS:-false}" == true ]]; then
  echo "::notice title=Android UI phase::Capturing bounded host and device connection diagnostics"
  timeout --kill-after=2s 5s adb devices -l > ui-device-status.log 2>&1 || true
  timeout --kill-after=2s 5s bash -o pipefail -c 'free -m; ps -eo pid,ppid,rss,comm --sort=-rss | head -n 20; cat /sys/fs/cgroup/memory.events 2>/dev/null' > ui-host-memory.log 2>&1 || true
  # Read-only kernel evidence, without command arguments or environment secrets.
  timeout --kill-after=2s 5s bash -o pipefail -c 'sudo -n dmesg | grep -Ei "out of memory|oom-kill|killed process|segfault|qemu|emulator" | tail -n 60' > ui-host-kernel.log 2>&1 || true
fi

echo "::notice title=Android UI phase::Capturing device log (limit ${adb_timeout}s)"
timeout --kill-after=5s "${adb_timeout}s" adb logcat -d -v threadtime > ui-logcat.log 2>&1 || echo "::warning title=Android UI diagnostics::Could not finish device log capture within its deadline"
echo "::notice title=Android UI phase::Device diagnostics finished; returning test status ${status}"
exit "$status"
