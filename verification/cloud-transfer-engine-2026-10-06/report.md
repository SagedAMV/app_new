# محرك نقل السحابة في الخلفية — تقرير التحقق (المرحلة 1)

**التاريخ:** 2026-10-06. **الفرع:** `main` · **الأداة:** `./build-release-full.sh` (النسخة الكاملة، بلا R8)
**النطاق:** طابور نقل (رفع/تنزيل) يستمر بعد إغلاق التطبيق، بإشعار فيه إيقاف مؤقت/استئناف/إلغاء، وباستثناءات قابلة لإعادة المحاولة بدل البدء من الصفر.

## 1) ما كان وما صار

| قبل | بعد |
|---|---|
| `CloudFilesViewModel.download()` و`FilesViewModel.confirmUpload()` يستدعيان `CloudSyncManager` مباشرة داخل `viewModelScope` ⇒ يُقتل النقل بإغلاق التطبيق، ولا يوجد إيقاف/استئناف، وأي فشل يعيد الطلب كاملًا | الطلب يُدرَج كـ**دفعة** في طابور دائم (DataStore) ويعمله `CloudTransferWorker` عبر WorkManager؛ إشعار مستمر بتقدّم وأزرار؛ الفاشل يبقى استثناءً يُعاد محليًا بلا إعادة ما اكتمل |

## 2) القرار المعماري ولماذا
- **وحدة العمل = دفعة لا ملف.** `prepareUploadPlan(folderIds=…)` يبني بنية المجلدات داخل `CloudUploadPlan`، و`uploadSelected(plan)` يستهلكها. التقسيم لكل ملف كان **سيُتلف بنية المجلد المرفوع** — عيب وظيفي حقيقي ظهر أثناء التصميم وقبل الكتابة.
- **لا شبكة جديدة ولا منطق مكرر:** الـworker يستدعي `CloudSyncManager.uploadSelected/downloadSelectedFiles` نفسها، فحالة التقدّم `_transferState` تظل تعمل، والبطاقة الحالية تعرضها بلا مصدر حقيقة ثانٍ.
- **التخزين DataStore + JSON** (نفس نمط `CloudCatalogStore`) بدل جدول Room: `UniHubDatabase` ما زال `version = 1` بلا هجرة، وإضافة جدول كانت ستلزم هجرة وترقيماً بلا داعٍ (§13).
- **منطق الطابور كائن نقي** (`CloudTransferQueueLogic`) بلا Android وبلا شبكة ⇒ يُختبر على JVM مباشرة (§29).

## 3) الإذن المضاف (كان سينهار بدونه)
`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`: على `targetSdk 35` يستدعي WorkManager خدمة أمامية نوعها `dataSync`، وبدون الإذن الثاني يفشل `startForeground` بـ`SecurityException`. رُفضا كان يعني **انهيارًا عند أول رفع بعد قفل الشاشة** — أسوأ من المشكلة الأصلية. كما أن `setForegroundAsync` نفسه داخل `runCatching`: إن رُفض بدء الخدمة الأمامية من الخلفية (قيود أندرويد 12+) يُكتفى بإشعار يدوي ويكمل النقل مجدولًا، بدل أن يفشل.

## 4) السيناريوهات ومصفوفة المخاطر (كُتبت قبل التنفيذ)

| ID | نوع | Persona · Goal · المخرجات وOracle المختصر | I | L | D | Risk |
|---|---|---|---|---|---|---|
| S1 | عادي | طالب يرفع 40 محاضرة ويغلق التطبيق ⇒ الطابور يفرغ، لا `PENDING` متبقٍّ، إشعار ختامي | 5 | 5 | 3 | **75** |
| S2 | عادي | طالبة تنزّل مجلدًا وتُوقف ثم تستأنف ⇒ لا يُعاد تنزيل المكتمل، الترتيب محفوظ | 4 | 4 | 3 | 48 |
| S3 | عادي | 3 دفعات متتابعة ⇒ تنفيذ متسلسل بلا `CloudBusyException` للواجهة | 4 | 3 | 2 | 48 |
| S4 | حدّي | انقطاع في المنتصف ⇒ `Result.retry()`، العنصر يبقى معلقًا، المحاولة لا تُستهلك | 5 | 4 | 2 | **120** |
| S5 | حدّي | ملف محلي حُذف أثناء الانتظار ⇒ `missingFiles` معلنة، الدفعة لا تنهار | 4 | 3 | 3 | 36 |
| S6 | حدّي | مجلد الوجهة اختفى ⇒ رسالة المدير الأصلية في `lastError` لا استثناء خام | 3 | 3 | 3 | 27 |
| S7 | فشل | مفاتيح سحابية لم تعد في الفهرس ⇒ فشل موصوف وبقية الدفعات سليمة | 4 | 2 | 4 | 16 |
| S8 | فشل | «إلغاء» أثناء الجريان ⇒ تُمسح المعلقة فقط ولا تراجع عن المكتمل | 3 | 3 | 2 | 36 |
| S9 | عدائي | نقرات متكررة بنفس الدفعات ⇒ إزالة التكرار النائم، FIFO حتمي | 3 | 2 | 3 | 18 |
| S10 | عدائي | DataStore تالف/JSON غير صالح ⇒ طابور فارغ سليم، بلا انهيار وبلا حذف بيانات | 5 | 1 | 3 | 15 |

**Invariants:** Inv-1 لا دفعتان معًا (احترام `mutex`). Inv-2 الانقطاع/الانشغال لا يُحسب محاولة. Inv-3 إيقاف/إلغاء لا يمسّان المكتمل. Inv-4 كل دفعة بحالة واحدة {PENDING, DONE, FAILED} ولا حالة معلّقة بعد انتهاء العامل. Inv-5 إشعار مستمر واحد (`9043`). Inv-6 لا مكتبة جديدة ولا HTML.
**Metamorphic:** MR-1 تكرار نفس الدفعة المجدولة ⇒ اللاتغيير. MR-3 إيقاف ← استئناف يعيد نفس مجموعة `PENDING` بالضبط.

## 5) التغيير الجوهري الذي طرأ على الخطة أثناء العمل (ليس تزيينًا)
كنت قررتُ في البداية طابورًا **لكل ملف** ليكون «الاستثناء» على مستوى الملف. بعد قراءة `uploadSelected` و`prepareUploadPlan` تبيّن أن الخطة تحمل بنية المجلدات، فالتقسيم كان سيكسر «رفع مجلد». **بدّلت الوحدة إلى دفعة**، وبقيت خاصة «لا نُعيد ما اكتمل» محققة لأن المدير نفسه يتخطى الموجود (`alreadyPresentCount` عبر `CloudUploadMergeRules`، و`matchExistingInDestination` في التنزيل). كذلك وُلدت حالة «تأجيل» (`Defer`) لم تكن في الحسبان، لأن عدّ الانقطاع فشلاً كان سيستنفد المحاولات الأربع أثناء انقطاع طويل فيحتاج تدخلاً يدويًا بلا سبب.

## 6) البوابات — الخرج الخام
```
compileReleaseFullKotlin      → G1_OK  BUILD SUCCESSFUL in 42s
testReleaseFullUnitTest       → G2_OK  BUILD SUCCESSFUL in 1m 3s
   TOTAL tests=234 skipped=3 failures=0 errors=0   (الأساس قبل العمل: 215)
   CloudTransferQueueLogicTest: tests=19 failures=0 errors=0
lintVitalReleaseFull          → G3_OK  BUILD SUCCESSFUL in 2m 18s
assembleReleaseFull           → G4_OK  EXIT=0
```
**فشلان حقيقيان أثناء الطريق (وقُيدا ثم صُححا، لا أُخفاؤهما):**
1. `CloudTransferQueue.kt:256 Unresolved reference 'ReplaceFileCorruptionHandler'` — سببه تنظيفي «الاستيرادات غير المستعملة» بحذف أسطر آليًا فحذفُ استيراد ضروري معه.
2. `CloudTransferWorker.kt:185 No value passed for parameter 'p0'` — `setSilent()` غير متوفر على `NotificationCompat.Builder` في `core-ktx 1.15`؛ حُذف لأنه زائد أصلًا (القناة `IMPORTANCE_LOW` صامتة).
3. خطأ ثالث كان في **اختباري** لا في التطبيق: `emptyList()` بلا نوع ⇒ «Cannot infer type». صُحّح إلى `assertTrue(...isEmpty())` بدل إرخاء الاسترجاع.

**تحقق على مستوى الحزمة (لا المصدر):** `aapt2 dump permissions` = `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` ✓؛ `receiver` = `com.unihub.app.notifications.CloudTransferActionsReceiver` (سطر 111) ✓؛ `grep` على `classes*.dex` (3 ملفات): `CloudTransferWorker`=12، `CloudTransferActionsReceiver`=6، `CloudTransferQueueStore`=9، `unihub_cloud_transfers`=1 ✓؛ النصوص العربية (`إيقاف مؤقت`, `إلغاء ما لم يبدأ`, …) = 1 لكلٍّ ✓؛ `zipalign: سليم`، `apksigner: التوقيع سليم`؛ `versionCode=5 / 1.3.1 / minSdk 26 / targetSdk 35`.

## 7) ما لم يُغطَّ (بلا ادعاء)
- **لم يُختبر على جهاز حقيقي**: النقر الفعلي على زر «إيقاف مؤقت» في الإشعار، وسلوك `setForegroundAsync` تحت قيود البطارية في أندرويد 14/15، واستمرار النقل بعد `Force stop`. اختبارات الآلة تغطي المنطق والحفظ فقط؛ instrumentation غير موجود في المشروع.
- `decodeQueueJson` مُختبَر على JVM (تبعية `org.json` موجودة أصلًا في `testImplementation`)، لكن **مسار تلف ملف DataStore نفسه** (`ReplaceFileCorruptionHandler`) لا يُختبر محليًا لاحتياجاته Robolectric — وهو نفس نمط `CloudCatalogStore` المثبَّت سلفًا في الإنتاج.
- **المرحلة 2 لم تُنفَّذ بعد** (بقرار `engine_first`): تكافؤ إجراءات التحرير في سحابة (نسخ/تكرار، تنزيل مجلد كامل، إنشاء مجلد، رفع صور/تسجيل صوتي، إضافة صورة) وزرّا «نقل» و«نقل ورفع للسحابة» في صندوق المشاركة.

## 8) الملفات
جديد: `data/cloud/CloudTransferQueue.kt` · `data/cloud/CloudTransferWorker.kt` · `notifications/CloudTransferActionsReceiver.kt` · `test/.../CloudTransferQueueLogicTest.kt`
مُعدَّل: `data/cloud/CloudSyncScheduler.kt` (جدولة `cloud_r2_transfers` بـ`APPEND` + `cancelTransfers`) · `notifications/NotificationChannels.kt` (`CLOUD_TRANSFERS`, `IMPORTANCE_LOW`) · `AndroidManifest.xml` (إذنَان + مستقبل) · `feature/files/CloudFilesViewModel.kt` (إدراج الطابور + إيقاف/استئناف/إعادة/إلغاء/إخفاء) · `feature/files/FilesViewModel.kt` (`confirmUpload` يُدرَج لا يُنفَّذ) · `feature/files/CloudFilesScreen.kt` (البطاقة صارت تُظهر الطابور والاستثناءات).
غير مسموسة عمدًا: `تعليمات.md` (0 تعديل)، ولا HTML (0 ملف)، ولا `largeHeap`، ولا تبعية جديدة (0).
