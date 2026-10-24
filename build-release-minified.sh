#!/usr/bin/env bash
# ============================================================================
# بناء «النسخة المصغّرة» (release مع R8 + shrinkResources) — غلاف معتمد.
#
# لماذا غلاف خاص؟ R8 يعمل داخل عملية Gradle نفسها، فتحدّده كومة الـ Daemon
# (org.gradle.jvmargs — المضبوطة على 832m عمداً لحماية مسار «النسخة الكاملة»
# على آلات 2GB بلا Swap، راجع تعليقها في gradle.properties). عند التصغير
# الكامل (R8 Full Mode) على هذا المشروع — Compose + Hilt + Room — تستهلك
# المرحلة أكثر من 832m فيفشل البناء بـ:
#   ERROR: R8: java.lang.OutOfMemoryError: Java heap space
# وهذا رُصد فعلياً في جلسة التحقق العميق 2026-10-02 (assembleRelease فشل،
# بينما النسخة الكاملة releaseFull نجحت). الحل هنا: تجاوز كومة الـ Daemon
# لهذا البناء وحده عبر -Dorg.gradle.jvmargs، فتبقى الإعدادات المحفوظة كما هي
# لمسار النسخة الكاملة وللأجهزة محدودة الذاكرة.
#
# الاستخدام:  ./build-release-minified.sh
# المتطلبات:  JDK 17+ (JAVA_HOME أو java في PATH) + أندرويد SDK (ANDROID_HOME
#             أو local.properties) — الغلاف يتحقق منهما قبل البدء.
# ملاحظة:     بوابات الترجمة والاختبارات وlint مغطاة في build-release-full.sh
#             على الشيفرة نفسها؛ هذا الغلاف غايته إنتاج الحزمة المصغّرة والتحقق
#             من توقيعها ومحاذاتها.
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

# --- اكتشاف البيئة -----------------------------------------------------------
if [ -z "${JAVA_HOME:-}" ]; then
  command -v java >/dev/null 2>&1 || { echo "خطأ: لا JDK — صدّر JAVA_HOME أولاً" >&2; exit 1; }
else
  export PATH="$JAVA_HOME/bin:$PATH"
fi
java -version 2>&1 | head -1

if [ -z "${ANDROID_HOME:-}" ] && [ ! -f local.properties ]; then
  echo "خطأ: لا ANDROID_HOME ولا local.properties — حدّد مسار SDK" >&2
  exit 1
fi

# كومة أكبر لمرحلة R8 فقط — بقية القيم تطابق gradle.properties حرفياً.
R8_JVM_ARGS="-Xmx2560m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8"
GRADLE=(./gradlew --no-daemon --console=plain "-Dorg.gradle.jvmargs=$R8_JVM_ARGS")

echo ""
echo "=== النسخة المصغّرة: assembleRelease (R8 + shrinkResources) ==="
"${GRADLE[@]}" assembleRelease

# --- التحقق من الحزمة الناتجة -------------------------------------------------
APK="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "خطأ: لم يُعثر على $APK" >&2; exit 1; }

SDK_DIR="${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties)}"
BT="$(ls -d "$SDK_DIR"/build-tools/* 2>/dev/null | sort -V | tail -1)"
if [ -n "$BT" ]; then
  "$BT/zipalign" -c 4 "$APK" && echo "zipalign: سليم"
  "$BT/apksigner" verify "$APK" && echo "apksigner: التوقيع سليم"
fi

echo ""
echo "نجح البناء — النسخة المصغّرة (R8 + shrinkResources، موقّعة بمفتاح الإصدار):"
ls -lh "$APK"
