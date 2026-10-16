#!/usr/bin/env bash
# Bound the whole test pipeline and diagnostics; never disguise a timeout as success.
set -euo pipefail

adb_timeout="${ANDROID_UI_ADB_TIMEOUT_SECONDS:-20}"
test_timeout="${ANDROID_UI_TEST_TIMEOUT_SECONDS:-480}"
# Grace period between the launcher smoke start and the "still alive" check, and the
# number of liveness probes after it (the app must survive a cold start, not just start).
smoke_grace="${ANDROID_UI_SMOKE_GRACE_SECONDS:-8}"
smoke_probes="${ANDROID_UI_SMOKE_PROBES:-3}"
for duration in "$adb_timeout" "$test_timeout" "$smoke_grace" "$smoke_probes"; do
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

if [[ "$status" != 0 && "${GITHUB_ACTIONS:-false}" == true ]]; then
  echo "::notice title=Android UI phase::Capturing bounded host and device connection diagnostics"
  timeout --kill-after=2s 5s adb devices -l > ui-device-status.log 2>&1 || true
  timeout --kill-after=2s 5s bash -o pipefail -c 'free -m; ps -eo pid,ppid,rss,comm --sort=-rss | head -n 20; cat /sys/fs/cgroup/memory.events 2>/dev/null' > ui-host-memory.log 2>&1 || true
  # Read-only kernel evidence, without command arguments or environment secrets.
  timeout --kill-after=2s 5s bash -o pipefail -c 'sudo -n dmesg | grep -Ei "out of memory|oom-kill|killed process|segfault|qemu|emulator" | tail -n 60' > ui-host-kernel.log 2>&1 || true
fi

# ── فحص إقلاع النسخة المثبَّتة كما يفتحها المستخدم من أيقونة التطبيق ──
# اختبارات الجهاز أعلاه تبني واجهاتها بنفسها (createComposeRule) فلا تمرّ على مسار الإقلاع
# الحقيقي (Application + Hilt + Room + WorkManager). هذا الفحص يشغّل النشاط الحقيقي بنيّة
# النظام، ثم يتأكد أن العملية ما زالت حيّة بعد مهلة سماح وأن مخزن الانهيارات نظيف — أي أنه
# يحوّل «النسخة تُثبَّت ثم لا تعمل» إلى فشل ظاهر بدل منصة خضراء.
launch_status=0
echo "::notice title=Android launch smoke::Starting the installed build from the system launcher"
: > ui-launch-smoke.log
timeout --kill-after=5s "${adb_timeout}s" adb logcat -c >/dev/null 2>&1 || true
timeout --kill-after=5s "${adb_timeout}s" adb shell am force-stop com.unihub.app >>ui-launch-smoke.log 2>&1 || true
if ! timeout --kill-after=5s "${adb_timeout}s" adb shell am start -W -n com.unihub.app/.MainActivity >>ui-launch-smoke.log 2>&1; then
  echo "::error title=Android launch smoke::The system could not start com.unihub.app/.MainActivity" | tee -a ui-launch-smoke.log
  launch_status=1
fi
sleep "${smoke_grace}"
alive=""
for _ in $(seq 1 "${smoke_probes}"); do
  if alive="$(timeout --kill-after=5s "${adb_timeout}s" adb shell pidof com.unihub.app 2>/dev/null | tr -d '\r')" && [[ -n "$alive" ]]; then
    break
  fi
  alive=""
  sleep 2
done
if [[ -z "$alive" ]]; then
  echo "::error title=Android launch smoke::The installed build is not running after its cold start; the APK does not work on a device" | tee -a ui-launch-smoke.log
  launch_status=1
else
  echo "::notice title=Android launch smoke::Process ${alive} is still alive after ${smoke_grace}s" | tee -a ui-launch-smoke.log
fi
if timeout --kill-after=5s "${adb_timeout}s" adb logcat -d -b crash > ui-crash-buffer.log 2>&1; then
  if grep -q "com\.unihub\.app" ui-crash-buffer.log; then
    echo "::error title=Android launch smoke::A crash of com.unihub.app was recorded during launch" | tee -a ui-launch-smoke.log
    grep -n -A 12 "com\.unihub\.app" ui-crash-buffer.log | tail -n 40 >> ui-launch-smoke.log
    launch_status=1
  fi
else
  echo "::warning title=Android launch smoke::Could not read the device crash buffer within its deadline" | tee -a ui-launch-smoke.log
fi
# سجلّ الأخطاء المقيَّد بمسار الانهيار فقط — يُقرأ منه ما يخصّ عملية التطبيق دون ضجيج النظام.
timeout --kill-after=5s "${adb_timeout}s" adb logcat -d -v threadtime -s AndroidRuntime:E libc:F > ui-launch-logcat.log 2>&1 || true
if grep -A 25 "FATAL EXCEPTION" ui-launch-logcat.log | grep -q "com\.unihub\.app"; then
  echo "::error title=Android launch smoke::AndroidRuntime reported a fatal exception for com.unihub.app" | tee -a ui-launch-smoke.log
  grep -n -A 25 "FATAL EXCEPTION" ui-launch-logcat.log | tail -n 40 >> ui-launch-smoke.log
  launch_status=1
fi
if [[ "$launch_status" != 0 ]]; then
  echo "::notice title=Android launch smoke::Launch verification failed; see ui-launch-smoke.log and ui-crash-buffer.log"
  if [[ "$status" == 0 ]]; then status="$launch_status"; fi
fi

echo "::notice title=Android UI phase::Capturing device log (limit ${adb_timeout}s)"
timeout --kill-after=5s "${adb_timeout}s" adb logcat -d -v threadtime > ui-logcat.log 2>&1 || echo "::warning title=Android UI diagnostics::Could not finish device log capture within its deadline"
echo "::notice title=Android UI phase::Device diagnostics finished; returning test status ${status}"
exit "$status"
