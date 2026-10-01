#!/usr/bin/env bash
# ============================================================================
# جولة تعليمات.md — فاحص ثابت (grep) يجيب سؤال «هل طُبّقت التعديلات فعلاً؟».
#
# تاريخ: كُتب في جلسة التعديل الأولى، ثم وُحّد بعد اكتشاف الجولة الموازية
# (cc907eb) على البعيد، فيفحص الآن الشجرة المدموجة النهائية: وحدات الجولة
# الرسمية (CloudBackPolicy / CloudDownloadDefaults / DestinationFolderTree)
# + الإضافات التي بقيت من هذه الجلسة (زر الرجوع في الكاميرا، طبقة البحث في
# شاشة الملفات، منع ازدواج معالجات الرجوع). الفاحص الآخر verify.sh في
# verification/session-fixes يفحص الجولة الأولى وحدها ويبقى كما هو.
#
# لماذا فاحص ثابت أصلاً؟ اختبارات الوحدة تثبت المنطق النقي، لكنها لا تثبت أن
# الشاشات استخدمته فعلاً. هذا الفاحص يربط الطرفين: كل تعديل مطلوب في
# تعليمات.md له أثر موجود في المصدر، وكل أثر قديم مطلوب إزالته قد أُزيل.
#
# الاستخدام:  bash verification/system-back-and-download-ux/check-changes.sh
# الخروج:     0 = كل البنود خضراء، 1 = بند واحد فأكثر فشل.
# ============================================================================
set -u
cd "$(dirname "$0")/../.."
MAIN="app/src/main/java/com/unihub/app"
TEST="app/src/test/java/com/unihub/app"
FAILS=0

pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILS=$((FAILS+1)); }

has()   { grep -rqF -- "$2" "$1" 2>/dev/null && pass "$3" || fail "$3"; }
lacks() { grep -rqF -- "$2" "$1" 2>/dev/null && fail "$3" || pass "$3"; }
count_eq() { # ملف، نمط، عدد متوقع، وصف
  local n; n=$(grep -cF -- "$2" "$1" 2>/dev/null || true)
  [ "${n:-0}" -eq "$3" ] && pass "$4 (العدد=$n)" || fail "$4 (العدد=$n والمتوقع=$3)"
}

echo "=== 1) زر الرجوع الخاص بالنظام (المشكلة الأولى) ==="
has  "$MAIN/feature/files/BackPolicy.kt" "object CloudBackPolicy" \
     "السياسة الموحدة النقية موجودة (CloudBackPolicy)"
has  "$MAIN/feature/files/CloudFilesScreen.kt" "CloudBackPolicy.step" \
     "شاشة السحابة تفكّك الطبقات عبر السياسة الموحدة"
has  "$MAIN/feature/files/CloudFilesScreen.kt" "BackHandler(onBack = handleBack)" \
     "شاشة السحابة تعترض زر الرجوع الخاص بالنظام"
has  "$MAIN/feature/files/FilesScreen.kt" "CloudBackPolicy.step" \
     "شاشة الملفات تفكّك الطبقات عبر السياسة الموحدة"
has  "$MAIN/feature/files/FilesScreen.kt" "setSearchQuery(\"\")" \
     "شاشة الملفات تُغلق البحث قبل الخروج من الشاشة"
count_eq "$MAIN/feature/files/FilesScreen.kt" "BackHandler(" 1 \
     "معالج رجوع واحد فقط في شاشة الملفات (لا ازدواج بعد الدمج)"
has  "$MAIN/feature/files/capture/CameraCaptureScreen.kt" "BackHandler" \
     "شاشة الكاميرا تعترض زر الرجوع الخاص بالنظام (لا ضياع للقطات)"
has  "$MAIN/feature/files/capture/CameraCaptureScreen.kt" "BackStep.WENT_UP -> phase = CapturePhase.CAPTURE" \
     "الرجوع من طور المراجعة يعود إلى الكاميرا لا خارج الشاشة"

echo ""
echo "=== 2) تسميات خيارات التنزيل والافتراضي (المشكلة الثانية) ==="
DIALOG="$MAIN/feature/files/CloudDownloadDestinationDialog.kt"
has  "$DIALOG" "ترتيب تلقائي .. موصى بة"     "التسمية الجديدة للخيار الموصى به"
has  "$DIALOG" "سحب ملفات فقط"                 "التسمية الجديدة لخيار الملفات دون أسماء السحابة"
has  "$DIALOG" "سحب مجلد كامل"                 "التسمية الجديدة لخيار المجلد مع فرعه"
has  "$DIALOG" "CloudDownloadDefaults.location" \
     "الافتراضي يُقرأ من المصدر الوحيد CloudDownloadDefaults"
has  "$MAIN/data/cloud/CloudDownloadDestination.kt" "val location: CloudDownloadLocation = CloudDownloadLocation.ORIGINAL_CLOUD_TREE" \
     "المصدر الوحيد للافتراضي يشير إلى «ترتيب تلقائي .. موصى بة»"
lacks "$DIALOG" "حسب شجرة مجلدات السحابة الأصلية"          "التسمية القديمة (شجرة السحابة) أُزيلت"
lacks "$DIALOG" "داخل مجلد أختاره"                          "التسمية القديمة (داخل مجلد أختاره) أُزيلت"
lacks "$DIALOG" "المجلد داخل وجهتي مع الاحتفاظ بمجلداته الفرعية" "التسمية القديمة (وجهتي مع الاحتفاظ) أُزيلت"

echo ""
echo "=== 3) شجرة المجلدات القابلة للتوسيع (المشكلة الثالثة) ==="
has  "$MAIN/feature/files/DestinationFolderTree.kt" "fun rows" \
     "باني صفوف الشجرة النقي موجود (DestinationFolderTree)"
has  "$DIALOG" "DestinationFolderTree.rows" \
     "حوار الوجهة يبني القائمة من الشجرة لا من مسارات مسطّحة"
has  "$DIALOG" "expandedIds" \
     "حالة التوسيع موجودة: نقر على المجلد يكشف فرعيّاته"
lacks "$DIALOG" "joinToString(\" / \")" \
     "المسارات المسطّحة «أ / ب» أُزيلت من حوار الوجهة"

echo ""
echo "=== 4) الاختبارات ووحدة التنفيذ والقيود الصارمة ==="
has  "$TEST/feature/files/CloudBackPolicyTest.kt" "class CloudBackPolicyTest" \
     "اختبارات السياسة مكتوبة"
has  "$TEST/feature/files/DestinationFolderTreeTest.kt" "class DestinationFolderTreeTest" \
     "اختبارات باني الشجرة مكتوبة"
lacks "$MAIN/core/common/BackNavigationPolicy.kt" "object BackNavigationPolicy" \
     "النسخة المكررة من السياسة حُذفت بعد الدمج (لا دوال/وحدات ميتة)"
lacks "$MAIN/data/repository/FolderTreeRows.kt" "object FolderTreeRows" \
     "النسخة المكررة من باني الشجرة حُذفت بعد الدمج"
has  "تعليمات.md" "قاعدة صارمة للحفظ" \
     "ملف تعليمات.md ما زال موجوداً في المستودع (القاعدة الصارمة 1)"
lacks "$MAIN" "<html" "لا يوجد أي ملف HTML في مصادر التطبيق (القاعدة الصارمة 3)"

echo ""
if [ "$FAILS" -eq 0 ]; then
  echo "النتيجة: كل البنود خضراء — التعديلات مطبّقة في المصدر المدموج."
  exit 0
else
  echo "النتيجة: $FAILS بنداً فشلت — التعديلات ناقصة."
  exit 1
fi
