# جلسة تحقق عميق مستقلة من البناء — 2026-10-02 (تسليم النسخة الكاملة 1.3.1 إلى buled)

## البيئة (بُنيت من الصفر هذه الجلسة)
- OpenJDK 21.0.12.1 (Debian) + Android SDK platform-35 + build-tools 35.0.0 +
  Gradle 8.9 (wrapper) + AGP 8.7.2 + Kotlin 2.0.21 + KSP 2.0.21-1.0.25
- آلة 2 vCPU / 1.9GB RAM — أُضيف Swap 6GB لهذه الجلسة (خارج إعدادات المشروع،
  لا علاقة له بضبط gradle.properties الموثق الذي بقي كما هو).

## منهج الجلسة: توسيع البوابات لا إعادة تشغيلها فقط
جلسات التحقق السابقة نفّذت أربع بوابات (compile / test / lintVital /
assembleReleaseFull). هذه الجلسة أضافت بوابات لم تُشغَّل من قبل على هذا
المشروع، فظهرت أخطاء حقيقية كانت خارج مدى الفحص القديم:

| البوابة | قبل الإصلاح | بعد الإصلاح |
|---|---|---|
| `:app:dependencies` (حل الاعتماديات) | ✅ 1m18s | — |
| `compileReleaseFullKotlin` | ✅ 1m41s (صفر تحذيرات) | ✅ 1m36s |
| `testReleaseFullUnitTest` | ✅ 1m — 146/0 فشل (3 متخطاة R2) | ✅ 50s — نفس النتيجة |
| `lintVitalReleaseFull` | ✅ 56s | — |
| **`lintReleaseFull` (lint كامل لا vital)** | ⚠️ 58 مشكلة **منها خطآن Error** | ✅ 47 مشكلة، **صفر Errors** |
| `assembleReleaseFull` (المسلَّمة) | ✅ 2m3s | ✅ 3m12s |
| **`assembleRelease` (المصغّرة R8)** | ❌ **فشل: R8 OOM** | ✅ 6m9s |
| `assembleDebug` | ✅ 4m9s | ✅ (تحقق مستقل) |

قراءة نتائج الاختبارات من XML مباشرة (لا الاكتفاء بـ«BUILD SUCCESSFUL»):
`classes=14 tests=146 failures=0 errors=0 skipped=3` — المتخطاة الثلاث هي
اختبارات R2 الحية في `R2ClientIntegrationTest` (معطّلة بتصميمها).

## الأخطاء المكتشفة والمُصلَحة (3 مشاكل حقيقية)

### 1) 🔴 خطأ بناء فعلي: فشل التصغير الكامل (R8 OutOfMemory)
```
> Task :app:minifyReleaseWithR8 FAILED
ERROR: R8: java.lang.OutOfMemoryError: Java heap space
BUILD FAILED in 7m 3s
```
- **السبب**: R8 في الوضع الكامل (Full Mode) يعمل داخل عملية Gradle نفسها،
  فتحدّه كومة الـ Daemon — وهي مضبوطة عمداً على `-Xmx832m` في
  `gradle.properties` لحماية مسار «النسخة الكاملة» على آلات 2GB (توثيق السبب
  موجود في الملف نفسه من جلسات سابقة). المرحلة تحتاج أكثر من 832m لهذا
  المشروع (Compose + Hilt + Room).
- **الإصلاح**: غلاف جديد `build-release-minified.sh` يتجاوز كومة الـ Daemon
  لهذا البناء وحده عبر `-Dorg.gradle.jvmargs="-Xmx2560m …"` (مُختبَر عملياً:
  الـ Daemon انطلق فعلاً بـ -Xmx2560m)، مع ملاحظة موثّقة في ذيل
  `gradle.properties` تشرح السبب وتوجّه إلى الغلاف — فلم تُرفع القيمة
  المحفوظة حتى لا يتضرر مسار releaseFull على الأجهزة الضيقة.
- **النتيجة بعد الإصلاح**: `assembleRelease` نجح (6m9s) وأنتج
  `app-release.apk` بحجم 3,031,191 بايت موقّعاً ومحاذى.

### 2) 🔴 خطأ lint بمستوى Error: MissingPermission
`CloudFileNotificationHelper.kt:93` — عرض إشعار بلا فحص صريح لإذن
`POST_NOTIFICATIONS` المطلوب على أندرويد 13+.
- **الإصلاح**: فحص صريح `checkSelfPermission(POST_NOTIFICATIONS)` محروساً
  بفحص إصدار (TIRAMISU) قبل العرض + imports اللازمة.

### 3) 🔴 خطأ lint بمستوى Error: RestrictedApi
`BackupRepository.kt:99` — استدعاء `InvalidationTracker.refreshVersionsSync()`
المقيّد بـ `LIBRARY_GROUP_PREFIX` في Room 2.6.1.
- **قرار الإصلاح موثّق**: الاستبدال ببديل Room العام
  (`refreshVersionsAsync`) كان سيُفسد آلية منع حلقة الرفع: تحقّقنا من بايت كود
  Room أن **المتزامنة تنفّذ refreshRunnable على الخيط الحالي قبل إعادة
  التحكم، وغير المتزامنة تجدوله على queryExecutor وتعود فوراً** — أي أن
  الإشعارات كانت ستصل بعد إنهاء الحارس فيراها `AutoBackupChangeWatcher`
  تعديلاً محلياً. لذلك أُبقي الاستدعاء مع `@SuppressLint("RestrictedApi")`
  وتعليق يشرح السبب والبديل المرفوض.

## تنظيف الكود الميت والبنية (فحص آلي معمّق + تحقق يدوي)
- **4 دوال بلا أي موضع استدعاء في كل الشيفرة (مصادر + اختبارات)** — حُذفت:
  - `BackupRepository.exportToFile(File)` و`importFromFile(File)` — بقايا مسار
    «ملف نسخة مؤقت/مسحوب» القديم الذي حلّ محله `exportToStream`/`export(uri)`.
  - `CloudflareR2Client.fetchRemoteMeta()` + `RemoteBackupMeta` — لا كاتب ولا
    قارئ لملف `unihub_cloud_meta.json` في التصميم الحالي (الفهرس الخفيف هو
    المعتمد)، وملف meta لم يبق منه إلا حمايته من الحذف.
  - `CloudSyncPreferences.markUploadPending()` — وُجد ما يغطي غرضه
    (`markLocalChange`).
- **حالة تُكتب ولا تُقرأ**: `lastRemoteApplyFinishedAt` (AtomicLong) — أُزيلت
  مع تصحيح التعليق: ضمان الترتيب يأتي من تسليم إشعارات Room قبل إنهاء الحارس.
- **imports يتيمة** بعد الحذف: `FileOutputStream`، `AtomicLong`، `JSONObject`.
- **ObsoleteSdkInt**: `mipmap-anydpi-v26` → `mipmap-anydpi` (minSdk=26).
- **UseTomlInstead**: إصدار `org.json:json:20240303` نُقل إلى كتالوج الإصدارات
  (`libs.json`).
- **AutoboxingStateCreation ×7**: حالات Compose البدائية صارت
  `mutableIntStateOf/mutableFloatStateOf/mutableLongStateOf`
  (AudioRecorderSheet ×4، CameraCaptureScreen ×3).

## تحقق عميق من الحزمة الناتجة (لا الحجم فقط)
- **النسخة الكاملة** `app/build/outputs/apk/releaseFull/app-releaseFull.apk`:
  - الحجم: 14,188,729 بايت — SHA-256:
    `cf8246abd6d7ba9de7cec7dd3d34640c093186b6ae1a54db3d94f109ef826e0b`
  - zipalign -c 4: سليم — apksigner: سليم بشهادة المستودع نفسها
    (CN=UniHub, OU=Personal, O=UniHub, C=SA — SHA-256 ‎`5109c404…b74ca`)
  - badging: `com.unihub.app` v1.3.1 (versionCode 5)، minSdk 26، targetSdk 35
  - 3 ملفات dex + مكتبات أصلية لأربع معماريات
  - فحص dex بـ apkanalyzer: `UniHubDatabase_Impl` و`WorkDatabase_Impl`
    **موجودتان مع مُنشئهما بلا وسائط `<init>()`** — أي أن السبب الجذري لكراش
    الإقلاع التاريخي غائب عن هذه الحزمة.
  - الدوال المحذوفة و`RemoteBackupMeta` و`lastRemoteApplyFinishedAt`:
    **لا أثر لها في الـ dex النهائي** (فحص strings).
- **النسخة المصغّرة** `app/build/outputs/apk/release/app-release.apk`:
  - 3,031,191 بايت (يكفُل أن قواعد R8 تحفظ مُنشئات كل
    `RoomDatabase` المولّدة: تحقق apkanalyzer على الحزمة نفسها)
  - `mapping.txt` (49MB) مُنتَج لتفكيك تتبعات الكراش مستقبلاً.

## ما لم يُلمس عمداً (موثّق للأمانة)
- تنبيهات lint المتبقية كلها إشعارات تحديث خارج شيفرة المشروع:
  40 `GradleDependency` + 2 `AndroidGradlePluginVersion` (ترقية الإصدارات
  قرار مستخدم لا جلسة تحقق)، 3 `ObsoleteLintCustomCheck` (ضجيج داخلي من
  jars lint الخاصة بـ androidx navigation)، 2 `UsableSpace` (اقتراح
  `getAllocatableBytes` — سلوك تجميلي).
- ملاحظة أمنية (بلا تغيير): اعتمادات R2 تعمل كقيم افتراضية مضمّنة في
  `CloudflareR2Config` — تصميم مقصود لتطبيق شخصي كي يعمل الاتصال تلقائياً.

## النتيجة النهائية
- صفر أخطاء lint (كان خطأين)، صفر تحذيرات Kotlin، صفر دوال ميتة، صفر `!!`
  في المصادر، صفر TODO/FIXME.
- البوابات كلها خضراء: compile + tests (146/0 فشل) + lint كامل + releaseFull
  + release المصغّرة + debug.
- الحزمة المسلَّمة: `UniHub-v1.3.1-release-full.apk` (SHA-256 ‎`cf8246ab…`)
  رُفعت إلى SagedAMV/buled.

ملاحظة أمانة: لم يُجرَ اختبار على جهاز حقيقي هذه الجلسة — البوابة النهائية
هناك تبقى تجربة المستخدم.
