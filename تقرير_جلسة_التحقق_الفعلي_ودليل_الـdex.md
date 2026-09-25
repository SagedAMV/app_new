# تقرير جلسة: تحقق عميق فعلي من البناء + دليل dex على سلامة النسخة المصغّرة

**التاريخ:** 2026 — **المستودع:** SagedAMV/app_new — **نطاق العمل:** تطبيق Kotlin فقط
(لم يُمَس أي ملف HTML، ولم تُحذف `تعليمات.md`)

---

## 1) المطلوب

تحقق عميق من أخطاء البناء، إصلاح ما يظهر، رفع التعديلات للمستودع، ثم بناء النسخة
المصغّرة (R8 + تقليص الموارد) ورفع ملف APK وحده إلى مستودع `SagedAMV/buled`.

## 2) البيئة (بناء حقيقي داخل الجلسة — لا اعتماد على جلسات سابقة)

| المكوّن | الإصدار |
|---|---|
| JDK | OpenJDK 17.0.20 (Debian) |
| Android SDK | platforms;android-35 + build-tools;34.0.0 + platform-tools |
| Gradle | 8.9 عبر wrapper المشروع |
| AGP / Kotlin / KSP | 8.7.2 / 2.0.21 / 2.0.21-1.0.25 (كما في libs.versions.toml) |
| ذاكرة | 1.9GB RAM + ملف swap بحجم 4GB أُنشئ خصيصاً لأن R8 يستهلك أكثر من الذاكرة المتاحة |

## 3) نتائج التحقق العميق

### أ) مراجعة الكود (60+ ملف Kotlin — قراءة طبقة طبقة)

- **مسح دوال ميتة آلي:** سكربت يستخرج كل إعلان `fun` ويطابقه ضد مواضع الاستخدام
  الفعلية. المرشحون الوحيدة: `doWork` (WorkManager)، `onCaptureSuccess` (CameraX)،
  `provideDatabase` (Hilt المولَّد)، ومحوّلات Room (`*ToStorage`/`*FromStorage` —
  يستدعيها كود Room المولَّد). **النتيجة: لا دوال ميتة حقيقية.**
- **مسح أنماط خطرة:** صفر `!!`، صفر `TODO(`/`FIXME`، صفر `GlobalScope`،
  صفر `catch` فارغة، صفر `println`/`Thread.sleep`.
- **سلامة تقليص الموارد:** مرجع موارد وحيد من الكود (`R.drawable.ic_notification`
  في ReminderWorker) وملفه موجود؛ البقية من manifest/XML (محمية تلقائياً).
- **قراءة معمّقة للمسارات الحرجة:** `DatabaseSelfHeal` (شبكة أمان + تحقق من نجاح
  الحذف)، `ThemePreferenceManager` (قراءة محصّنة + استشفاء كتابة)، `BackupRepository`
  (ZIP + توافق عكسي + حد zip-bomb + ترتيب مفاتيح أجنبية)، `FileStorage` (اسم فريد
  موحّد + إعادة تسمية فعلية + `importBytes` للاستعادة)، `ReminderScheduler`
  (أسماء أعمال مستقرة + حارس `delay <= 0`)، `NextLectureResolver` (منطق نقي
  مُختبَر)، وكل المستودعات والـ DAOs. **لا خلل منطقياً.**

### ب) البناء الفعلي: `assembleRelease` + `testReleaseUnitTest`

```
BUILD SUCCESSFUL in 11m 45s — 63 actionable tasks: 63 executed
```

- `kspReleaseKotlin` (توليد Room + Hilt): نجاح (مع تحذيرات KSP/Analysis-API
  المعروفة وغير المؤثرة).
- `compileReleaseKotlin` (Kotlin 2.0.21 + Compose compiler): نجاح بلا أخطاء.
- `hiltAggregateDepsRelease` / `hiltJavaCompileRelease`: نجاح.
- `minifyReleaseWithR8` (full mode + proguard-rules.pro): نجاح، وأنتج mapping.txt.
- `lintVitalRelease`: نجاح.
- التوقيع بمفتاح `keystore/unihub-release.jks`: نجاح.
- **اختبارات الوحدة: 25 اختباراً، صفر فشل**
  (DateFormatsTest 6 + NextLectureResolverTest 9 + InputValidatorTest 10 —
  النتائج محفوظة في `app/build/test-results/testReleaseUnitTest/`).

**لم يظهر أي خطأ بناء — لم يكن هناك ما يُصلح في الكود.** التعديل الوحيد المرفوع
هذا التقرير نفسه (توثيقاً لجلسة التحقق، على نمط تقارير الجلسات السابقة).

### ج) الجديد في هذه الجلسة: دليل على مستوى الـ dex (أعمق من «البناء نجح»)

فحصت الجلسات السابقة نجاح البناء فقط. هذه الجلسة فتحت APK النهائي وفككت
`classes.dex` بأداة `dexdump` (من build-tools;34.0.0) وتحققت من السلسلة الكاملة
التي كانت تسبب كراش الإقلاع التاريخي — **اسم الصنف + المُنشئ + مرجع الـ manifest**:

| الفحص | النتيجة |
|---|---|
| `androidx.work.impl.WorkDatabase_Impl` موجود في الـ dex | ✅ |
| مُنشؤه بلا وسائط `<init>()V` باقٍ (هو ما كان R8 يُسقطه) | ✅ |
| `com.unihub.app.data.local.UniHubDatabase_Impl` + مُنشئ `()V` | ✅ |
| `androidx.work.WorkManagerInitializer` موجود + مُنشئ `()V` | ✅ |
| الـ manifest المدمج يشير إلى `androidx.work.WorkManagerInitializer` داخل `InitializationProvider` — والاسم يطابق الصنف الباقي في الـ dex (السلسلة غير مقطوعة) | ✅ |
| `ReminderWorker` يحتفظ بمُنشئ `(Context, WorkerParameters)` الذي يُنشئه WorkManager انعكاسياً | ✅ |
| التمويه فعّال فعلاً (حقول `WorkDatabase_Impl` معتماة `I2/r`… أي أن R8 اشتغل ولم يتعطّل) | ✅ |

هذا تحديداً الفحص الذي لو طُبّق في جلسة الكراش التاريخية لاكتشف المشكلة:
الوجود في الـ dex وحده لا يكفي — **المُنشئ `()V` يجب أن يبقى**، وهو باقٍ الآن
بفضل قاعدة `-keep class * extends androidx.room.RoomDatabase { <init>(); }`.

## 4) النسخة المصغّرة المرفوعة

| الخاصية | القيمة |
|---|---|
| المسار المحلي | `app/build/outputs/apk/release/app-release.apk` |
| الحجم | 2,800,910 بايت (≈ 2.7 م.ب) |
| الحزمة / الإصدار | `com.unihub.app` — versionCode 1 / versionName 1.0.0 |
| التوقيع | CN=UniHub (مفتاح المستودع) — `apksigner verify` ناجح |
| SHA-256 | `770e235a27ea41d0421777baea78f601a3fd23673dd5b34f15cb885349b743dc` |

رُفعت إلى مستودع `SagedAMV/buled` باسم `UniHub-v1.0.0-release-minified.apk`
(استبدال النسخة السابقة — نفس الحجم لكن تجزئة مختلفة، أي بِناء جديد فعلاً).

> **البوابة النهائية تبقى جهازك:** ثبّت النسخة وتحقق من الإقلاع الفوري،
> إضافة مهمة/امتحان وظهور تذكيرهما، استيراد ملف وفتحه ومشاركته باسمه الحالي،
> وتصدير/استيراد نسخة احتياطية.
