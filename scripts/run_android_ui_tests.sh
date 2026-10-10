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

echo "::notice title=Android UI phase::Capturing device log (limit ${adb_timeout}s)"
timeout --kill-after=5s "${adb_timeout}s" adb logcat -d -v threadtime > ui-logcat.log 2>&1 || echo "::warning title=Android UI diagnostics::Could not finish device log capture within its deadline"
echo "::notice title=Android UI phase::Device diagnostics finished; returning test status ${status}"
exit "$status"
