#!/usr/bin/env bash
# طريقة التفصّح المطلوبة في تعليمات.md («وحاول تبتكر طريقة تفحص تعديلات هل طبقت او لا»):
# سكربت فحص قابل لإعادة التشغيل يثبت — بأدلة نصية من الشيفرة — أن تعديلات الجلسة
# (زر رجوع النظام، تسميات خيارات التنزيل والافتراضي، شجرة مجلدات الوجهة) مطبّقة فعلاً.
#
# التشغيل من أي مكان:  bash verification/session-fixes/verify.sh
# النتيجة: رمز خروج 0 = كل الفحوص ناجحة، 1 = فحص واحد أو أكثر فشل.
set -u
cd "$(dirname "$0")/../.." || exit 1

PASS=0
FAIL=0

check() { # الاسم ثم أمر الفحص
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then
    echo "PASS  $name"; PASS=$((PASS + 1))
  else
    echo "FAIL  $name"; FAIL=$((FAIL + 1))
  fi
}

check_not() { # الاسم ثم سلسلة البحث ثم الملف — تنجح إذا غابت السلسلة
  local name="$1"; local pattern="$2"; local file="$3"
  if grep -qF -- "$pattern" "$file"; then
    echo "FAIL  $name"; FAIL=$((FAIL + 1))
  else
    echo "PASS  $name"; PASS=$((PASS + 1))
  fi
}

CLOUD="app/src/main/java/com/unihub/app/feature/files/CloudFilesScreen.kt"
FILES="app/src/main/java/com/unihub/app/feature/files/FilesScreen.kt"
DIALOG="app/src/main/java/com/unihub/app/feature/files/CloudDownloadDestinationDialog.kt"
TREE="app/src/main/java/com/unihub/app/feature/files/DestinationFolderTree.kt"
DATA="app/src/main/java/com/unihub/app/data/cloud/CloudDownloadDestination.kt"
POLICY="app/src/main/java/com/unihub/app/feature/files/BackPolicy.kt"

echo "=== المشكلة 1: زر رجوع النظام ==="
check "P01 CloudFilesScreen يعترض زر النظام (BackHandler)"            grep -qF "BackHandler(onBack = handleBack)" "$CLOUD"
check "P02 السلّم يُلغي التحديد أولاً"                                grep -qF "CLEARED_SELECTION -> selectedKeys = emptySet()" "$CLOUD"
check "P03 السلّم يصعد للمجلد الأب"                                   grep -qF "WENT_UP -> currentKey = current?.parentKey" "$CLOUD"
check "P04 السلّم يخرج عند الجذر فقط"                                 grep -qF "EXITED -> onBack()" "$CLOUD"
check "P05 زر الشريط العلوي يستخدم السلّم نفسه"                       grep -qF "IconButton(onClick = handleBack)" "$CLOUD"
check "P06 سياسة الرجوع معزولة كوحدة نقية قابلة للاختبار"             grep -qF "object CloudBackPolicy" "$POLICY"
check "P07 FilesScreen يخرج من التحديد بدل الشاشة"                    grep -qF "BackHandler(enabled = isSelecting) { viewModel.clearSelection() }" "$FILES"
check "P08 اختبار سياسة الرجوع موجود"                                 test -f "app/src/test/java/com/unihub/app/feature/files/CloudBackPolicyTest.kt"

echo ""
echo "=== المشكلة 2: تسميات خيارات التنزيل والافتراضي ==="
check "P09 خيار «سحب ملفات فقط»"                                      grep -qF "سحب ملفات فقط" "$DIALOG"
check "P10 خيار «سحب مجلد كامل»"                                      grep -qF "سحب مجلد كامل" "$DIALOG"
check "P11 خيار «ترتيب تلقائي .. موصى بة»"                            grep -qF "ترتيب تلقائي .. موصى بة" "$DIALOG"
check "P12 الحوار يفتح على الخيار الموصى به (مصدر وحيد)"              grep -qF "mutableStateOf(CloudDownloadDefaults.location)" "$DIALOG"
check "P13 المصدر الوحيد في طبقة البيانات"                            grep -qF "val location: CloudDownloadLocation = CloudDownloadLocation.ORIGINAL_CLOUD_TREE" "$DATA"
check "P14 اختبار يثبّت الافتراضي"                                    grep -qF "defaultLocationIsTheRecommendedCloudTreeFromSingleSource" "app/src/test/java/com/unihub/app/data/cloud/CloudDownloadPlacementTest.kt"

echo ""
echo "=== المشكلة 3: شجرة مجلدات الوجهة ==="
check "P15 الحوار يستخدم الشجرة القابلة للتوسيع"                      grep -qF "DestinationFolderTree.rows" "$DIALOG"
check "P16 سهم توسيع/طي موجود"                                        grep -qF "ExpandMore" "$DIALOG"
check "P17 إزاحة حسب العمق"                                           grep -qF "row.depth * 18" "$DIALOG"
check "P18 حارس الدورات في الشجرة"                                    grep -qF "if (!seen.add(folder.id)) return" "$TREE"
check "P19 اختبار الشجرة موجود"                                       test -f "app/src/test/java/com/unihub/app/feature/files/DestinationFolderTreeTest.kt"
check_not "P20 القائمة المسطحة القديمة أُزيلت"                        "sortedBy(::path)" "$DIALOG"

echo ""
echo "=== قيود الجلسة ==="
check "P21 ملف تعليمات.md ما يزال موجوداً (قاعدة صارمة)"             test -f "تعليمات.md"
check "P22 لا ملفات HTML أُنشئت داخل تطبيق Kotlin"                    bash -c '! git ls-files --others --exclude-standard | grep -qi "\.html$"'

echo ""
echo "----------------------------------------"
echo "النتيجة: $PASS ناجح / $FAIL فاشل"
[ "$FAIL" -eq 0 ]
