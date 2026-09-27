#!/usr/bin/env bash
# ============================================================================
# بناء «النسخة الكاملة» (releaseFull) على مرحلتين — غلاف رسمي معتمد.
#
# لماذا مرحلتان؟ على الآلات محدودة الذاكرة (2GB بلا Swap) يقتل قتّال النظام
# الـ Daemon أثناء دمج الـ dex إن سبقته الترجمة وlint في العملية نفسها: أصناف
# مترجم كوتلن تبقى محتجزة في Metaspace (~450m) فوق الكومة لحظة ذروة d8
# فيتجاوز RSS الإجمالي ذاكرة الآلة (التفاصيل في gradle.properties). الفصل إلى
# استدعاءين يعيد JVM نظيفة لكل مرحلة، وخبيئة غرادل تربط المرحلتين بلا إعادة
# عمل. على الآلات الواسعة يمكن تنفيذ assembleReleaseFull مباشرة.
#
# الاستخدام:  ./build-release-full.sh
# المتطلبات:  JDK 17 (JAVA_HOME أو java في PATH) + أندرويد SDK (ANDROID_HOME
#             أو local.properties) — الغلاف يتحقق منهما قبل البدء.
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

GRADLE="./gradlew --no-daemon --console=plain"

# --- المرحلة 1: بوابات الجودة (ترجمة + اختبارات وحدة + lint حاسم) -------------
# إن فشلت أي بوابة هنا يتوقف الغلاف (set -e) قبل لمس التجميع.
echo ""
echo "=== المرحلة 1/2: الترجمة واختبارات الوحدة وlintVital ==="
$GRADLE compileReleaseFullKotlin testReleaseFullUnitTest lintVitalReleaseFull

# --- المرحلة 2: التجميع والتوقيع (Daemon جديد بذاكرة نظيفة) -------------------
echo ""
echo "=== المرحلة 2/2: تجميع النسخة الكاملة وتوقيعها ==="
$GRADLE assembleReleaseFull

# --- التحقق من الحزمة الناتجة -------------------------------------------------
APK="app/build/outputs/apk/releaseFull/app-releaseFull.apk"
[ -f "$APK" ] || { echo "خطأ: لم يُعثر على $APK" >&2; exit 1; }

SDK_DIR="${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties)}"
BT="$(ls -d "$SDK_DIR"/build-tools/* 2>/dev/null | sort -V | tail -1)"
if [ -n "$BT" ]; then
  "$BT/zipalign" -c 4 "$APK" && echo "zipalign: سليم"
  "$BT/apksigner" verify "$APK" && echo "apksigner: التوقيع سليم"
fi

echo ""
echo "نجح البناء — النسخة الكاملة (غير المصغّرة، موقّعة بمفتاح الإصدار):"
ls -lh "$APK"
