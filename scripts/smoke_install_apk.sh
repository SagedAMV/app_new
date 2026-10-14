#!/usr/bin/env bash
# Exercise the exact APK that will be published, without Gradle's test-only install flags.
set -euo pipefail
apk="${1:-app/build/outputs/apk/debug/app-debug.apk}"
[[ -s "$apk" ]] || { echo "::error::Missing APK: $apk"; exit 1; }
package="com.unihub.app"

# Only use a disposable CI emulator. Never uninstall a user's app or its data.
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || {
  echo "::error::Installation smoke test requires an emulator"
  exit 1
}
if adb shell pm path "$package" | grep -q '^package:'; then
  timeout 30s adb uninstall "$package"
fi
timeout 90s adb install --no-streaming "$apk"
# Also ensure that this same signed APK can be installed as an update.
timeout 90s adb install --no-streaming -r "$apk"
timeout 30s adb shell am start -W -n "$package/com.unihub.app.MainActivity" | tee install-launch.log
grep -q 'Status: ok' install-launch.log
adb shell dumpsys package "$package" | grep -E 'versionCode=|versionName=|minSdk=|targetSdk='
echo "Installation and activity launch succeeded on API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
