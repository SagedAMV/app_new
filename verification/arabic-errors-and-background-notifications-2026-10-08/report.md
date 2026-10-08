# تقرير تصحيح: رسائل الأعطال بالعربية + إشعارات النقل خارج التطبيق — 2026-10-08

الشكوى المُبلَّغ عنها (نص المستخدم):
1. «عندما أرفع ملفات أو أسحب ملفات وأوقف الإنترنت بنص التحميل يظهر لي إشارة خطأ باللغة الإنجليزية كأنه كود في التطبيق وليس كلامًا مثل «خطأ في التحميل».
2. «إشعارات عملية النقل/السحب/الرفع تظهر عندما أكون في التطبيق ولا تظهر إذا كنت خارج التطبيق أستعمل تطبيقات أخرى».

---

## 1) السبب الجذري — مُثبَت بقراءة الشيفرة لا بالاستنتاج

### العطل 1: العربية كانت **احتياطًا** لا أصلًا
كل مواضع العرض مكتوبة `error.message ?: "نص عربي"`؛ متى كان للاستثناء نصّ (وهو في الشبكة دائمًا إنجليزي) مُرِّر خامًا إلى الواجهة:

| الموضع | ما كان يظهر |
|---|---|
| `data/cloud/CloudTransferWorker.kt:136-138` | `UnknownHostException: Unable to resolve host "api.r2.dev": No address associated with hostname` — في **إشعار** الطابور و**بطاقة** الطابور معًا (لأن `lastError` يُبنى منه) |
| `data/cloud/CloudSyncManager.kt:1104` | `recordSyncFailure(error.message …)` — سجلّ المزامنة في الإعدادات |
| `data/cloud/CloudScanController.kt:58` | «تعذّر الفحص» بالنص الإنجليزي |
| `feature/files/CloudFilesViewModel.kt` (6 مواضع) | `SocketTimeoutException: Read timed out` على هيئة snackbar |
| `feature/files/FilesViewModel.kt` (6 مواضع)، `feature/backup/BackupViewModel.kt` (3)، `data/backup/AutoBackupExporter.kt:130` | مثلها |

ومصدر أرقام الحالات: `CloudflareR2Client.requireSuccess` كان يرمي `IOException("فشل الاتصال بخادم R2 (HTTP $status)")` — عربي لكنه **تقني**، فيخترق §5 «لا تُعرض حالات تقنية» من بوابة أخرى.

لا كان يوجد في المشروع أي مُطبِّق رسائل (بحث `fun errorMessage` لم يُرجع إلا `FileOpener.kt:23` غير مختص) — فالتسريب لم يكن استثناءً شاذًا بل غياب الطبقة كلها.

### العطل 2: ظهور الإشعار كان **مشروطًا بردّ** `setForegroundAsync`
`CloudTransferWorker.publishProgress` السابق:

```kotlin
val foreground = runCatching { setForegroundAsync(ForegroundInfo(…)) }.isSuccess
if (!foreground) CloudTransferNotifier.show(appContext, notification)
```

`setForegroundAsync` لا يستثني عندما يرفض النظام بدء خدمة أمامية من الخلفية — **يردّ `false`** داخل `ListenableFuture` لا يُنتظَر، فـ`isSuccess` تصير `true` أبدًا ⇒ الفرع الاحتياطي لا يعمل إطلاقًا. النتيجة:

* داخل التطبيق: الخدمة الأمامية مسموحة ⇒ النظام يعرض إشعارها ⇒ «الإشعار شغال».
* خارج التطبيق: أندرويد 12+ يمنع بدء خدمة أمامية من الخلفية، وأندرويد 14 يقيّد `dataSync` ⇒ لم تُبدأ الخدمة **ولم يُنشر إشعار يدوي** ⇒ لا شيء يظهر للمستخدم وهو في تطبيق آخر.

عوامل مُثبَت أنها **ليست** السبب (لكي لا يُعاد تشخيصها):
* تهيئة WorkManager عند الطلب (`AndroidManifest.xml:100-109` يحذف `WorkManagerInitializer`) مع `Configuration.Provider` في `UniHubApplication` — نمط مدعوم، ولو كان معطوبًا لتعطّلت الجدولة لا الإشعار.
* `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` معلنان في الـmanifest (سطران 18-19) فليس خطأ «نوع خدمة ناقص».
* `POST_NOTIFICATIONS` يُطلب في `MainActivity:45-58`، وقناة `cloud_transfers` تُنشأ في `NotificationChannels.create` (المستدعاة من `UniHubApplication.onCreate`).

---

## 2) الإصلاح

### طبقة معجم واحدة لكل الأعطال — `data/cloud/CloudFailureMessages.kt` (JVM خالص)
* `userMessage(error, networkAvailable)` — **معجم مغلق**: 16 نصًا إنسانيًا فقط يجوز أن يراه المستخدم (`UNREACHABLE`, `TIMEOUT`, `TLS`, `DROPPED`, `MISSING_LOCAL`, `OFFLINE`, `INTERRUPTED`, `SESSION`, `GONE_REMOTE`, `CONFLICT`, `TOO_BIG`, `THROTTLED`, `SERVER`, `NO_SPACE`, `FOLDER_ACCESS`, `GENERIC`)، وكلها تُسمَّى كثوابت يستعملها الاختبار بدل نسخ النصوص.
* **الأولوية للعربية المكتوبة يدويًا**: `(error.message)?.takeIf { isUserFacing(it) }` — فكل رسائل المشروع العربية (مثل `CloudConflictException`) تمرّ كما هي، ولا يُعاد كتابتها في مكانين.
* **فحص مزدوج** لئلا يمرّ نص مختلط: يشترط وجود محرف عربي **و** خلوّه من آثار تقنية (`exception`, `java.`, `okhttp`, `unable to`, `errno`, `code 404`…) — قاعدة «فيه عربية ⇒ اتركه» وحدها كان يخترقها خادمٌ يردّ «تعذّر Unable to resolve host».
* يمسح الاستثناء **وسببياته حتى عمق 4**، فيلتقط `UnknownHostException` الدفين داخل `IOException` من OkHttp.
* `networkAvailable=false` تطغى: «انتهت مهلة» و«لا إنترنت» سببان مختلفان تمامًا لسلوك المستخدم.
* `messageFor(status)` تعرب حالات HTTP **من منبعها** في `CloudflareR2Client.requireSuccess`، ويذهب الرقم إلى `Log.w` وحده.
* `or(error, fallback)` لمواضع التحقق المحلي (أسماء الملفات/الصلاحيات) حيث البديل الأدقّ تعرفه الواجهة.

وُصِلت الطبقة بكل موضع عرض في مسارات السحابة والنسخ (21 موضعًا)؛ لم تُمَسّ مواضع `SettingsViewModel`/`AuthViewModel`/`planner` لأن رسائلها كلها `require(...)` عربية ولا طريق شبكة عندها (سبر: لا `.message ?:` بقي في `data/cloud` و`data/backup` و`feature/files` و`feature/backup` إلا في تعليق موثّق).

### الإشعار مستقل عن خدمة الإبقاء، والنتيجة على قناة عالية الأهمية
```kotlin
CloudTransferNotifier.show(appContext, notification)                                   // دائمًا أولًا
runCatching { setForeground(ForegroundInfo(NOTIFICATION_ID, notification)) }
    .onFailure { Log.w(TAG, "رفض النظام خدمة أمامية لهذا النقل؛ إشعار الطابور يعمل وحده", it) }
```
* `setForeground` (النسخة الـsuspend) بدل `setForegroundAsync` حتى يكون الرفض **استثناءً مرئيًا** لا مستقبلاً غير مُنتظَر، ويُستعمَل للإبقاء فقط.
* `CloudTransferNotifier.queued(…)` + `CloudTransferControls.announceQueued()`: يُنشر إشعار «أُضيف إلى النقل في الخلفية» **لحظة القبول** من `CloudFilesViewModel.enqueue` و`FilesViewModel.confirmUpload` — فلا ينتظر أول دفعة ليثبت للمستخدم أن طلبه دخل.
* `CloudTransferNotifier.showResult(…)` على **`NotificationChannels.CLOUD_FILES`** (`IMPORTANCE_HIGH`) برقم `RESULT_ID = 9046` بعد تفريغ الطابور، بنصّ من `CloudTransferQueueLogic.outcomeNotificationText(snapshot, completedItems)`: هو وحده ما يعطي **heads-up** وهو داخل تطبيق آخر (قناة النقل `LOW` عمدًا — تقدّم مستمر لا يجوز أن يرنّ كل ثانية، §3 البساطة و§5 الأدب مع المستخدم).
* الصمت عند النجاح الكامل: لا يُنَبَّه المستخدم لكل رفع؛ يُنَبَّه عند تعذّر شيء أو توقفه. ومشروط بـ`completed > 0 || failed.isNotEmpty()` حتى لا يُعاد التنبيه لنفس الاستثناءات القديمة كل جولة.
* `outcomeNotificationText` تحسب «اكتمل» من **عدد هذا التشغيل** لا من الطابور: المنتهية تبقى محفوظة للمقارنة (`KEEP_FINISHED = 12`) فجمعها كان سيقرأ المستخدم أرقام دفعات قديمة.
* توحيد بوابة الإذن في `post(context, id, notification)`: فحص `areNotificationsEnabled` + إذن 33+ **مرة واحدة**، والفشل في النشر يُسجَّل ولا يُلغي النقل (`runCatching`) — الإشعار عرض، النقل هو العمل.

---

## 3) البوابات — الأوامر ومخرجاتها الحرفية

الأمر: `sh /home/user/run-gates.sh` (و`install .toolchain` ثم `sh ./gradlew --no-daemon` لأربع بوابات)

| البوابة | النتيجة |
|---|---|
| G1 `compileReleaseFullKotlin` | `G1_OK` |
| G2 `testReleaseFullUnitTest` | `suites=22 tests=272 failures=0 errors=0 skipped=3` |
| G3 `lintVitalReleaseFull` | `G3_OK` |
| G4 `assembleReleaseFull` | `G4_OK`، `BUILD SUCCESSFUL` |

الاختبارات المضافة في هذه الجولة (كلّها تُشغَّل فعلًا، بلا تجميل — §4.5.4):
* `CloudFailureMessagesTest` — **18** اختبارًا، أهمها الخاصية `noRawNetworkMessageEverReachesTheUser`: 20 رسالة واقعية (OkHttp/SSL/errno/`HTTP 404`/`Retrofit … 503`/`null`) × ثلاثة أنواع استثناءات، والمُخرج يجب أن ينتمي إلى `USER_FACING` حصرًا.
* `CloudTransferQueueLogicTest` — من 23 إلى **29**: ستة اختبارات لسطر إشعار النتيجة (صمت عند النجاح، ذكر العدد عند التعذّر، عدم تضخيم «اكتمل»، ذكر الإيقاف المؤقت، وعدم التسريب).
* اختبار واحد كان **يُشرعن العطل** وقد قُلِب لا حُذف: `CloudScanControllerTest.networkErrorIsNotMisreportedAsNoData` كان يطالب بـ`message.contains("403")`؛ صار يطالب بـ`isUserFacing(message)` و`assertEquals(SESSION, message)` و`assertFalse(contains("403"))` — مع بقاء assertion الأصلي على `CloudScanPhase.ERROR` (أن العطل لا يُبلَّغ «لا توجد بيانات»).

دليل على مستوى الشحنة النهائية (`classes*.dex` من `app-releaseFull.apk`، 14,483,717 بايت):
```
3  com/unihub/app/data/cloud/CloudFailureMessages     1  outcomeNotificationText
7  CloudFailureMessages                                1  showResult     2  announceQueued
1  انقطع الاتصال أثناء النقل؛ ستُعاد المحاولة من حيث توقف
1  تعذّر الوصول إلى خادم السحابة — تحقّق من الاتصال بالإنترنت
0  فشل الاتصال بخادم R2 (HTTP      ← مصدر أرقام الحالات أزيل من الشحنة نفسها
zipalign: سليم | apksigner verify: CN=UniHub (SHA-256 5109c404…74ca)
CJK scan: 0 محرفًا صينيًا/يابانيًا/كوريًا في الملفات الجديدة والمعدّلة
```

---

## 4) مصفوفة السيناريو → الاختبار (10 سيناريوهات: 3 عادي / 3 حدّي / 2 فشل متوقع / 2 عدائي)

| ID | نوع | السيناريو | تغطية قابلة للتشغيل محليًا | الحالة |
|---|---|---|---|---|
| F1 | عادي | رفع 12 ملفًا والشبكة سليمة ⇒ لا نص إنجليزي، والإشعار قبل نجاح FGS | `noRawNetworkMessageEverReachesTheUser` + فحص بنيوي (`show` قبل `setForeground`) | ✅/⚠️ |
| F2 | عادي | إعادة تسمية بعد انتهاء الجلسة (401/403) | `httpStatusesBecomeHumanReasonsWithoutNumbers` | ✅ |
| F3 | عادي | حذف ملف غاب عن الخادم (404) | `mixedArabicAndTechnicalIsRejectedNotKept`, `httpStatuses…` | ✅ |
| F4 | حدّي | **إطفاء الشبكة في منتصف تنزيل 300MB** | `unknownHostIsExplainedNotDumped`, `droppedStreamIsToldAsDisconnect`, `offlineOverridesAnyNetworkFault` | ✅/⚠️ |
| F5 | حدّي | 5xx/429 من الخادم | `httpStatuses…` | ✅ |
| F6 | حدّي | استثناء بـ`message = null` | `nullThrowableAndNullMessageNeverPrintNull` | ✅ |
| F7 | فشل متوقع | ملف محلي حُذف (`ENOENT`) | `missingLocalFileSaysSoInArabic`, `arabicProjectMessagesAreKeptVerbatim` | ✅ |
| F8 | فشل متوقع | بدء خارج التطبيق مع رفض خدمة أمامية | فحص بنيوي (الترتيب، `Log.w`) + لا تسريب | ⚠️ |
| F9 | عدائي | رسالة تقنية خالطها عربي («تعذّر Unable to resolve host») | `mixedArabic…`, `isUserFacingRejectsEverythingTechnicalOrEmpty`, `everyUserTextIsArabicAndTechnicalFree` | ✅ |
| F10 | عدائي | 40 دفعة سريعة/إشعار نتيجة مكرر | `outcomeIsSilentOnPureSuccess`, `outcomeNamesFailures…`, `staleCompletedBatchesDoNotInflate…`, `outcomeMentionsPause…` | ✅/⚠️ |

**Invariants:** I1 لا نص تقني للمستخدم — ✅ (`everyUserTextIsArabicAndTechnicalFree` + الخاصية). I2 كل رسالة فيها ≥1 محرف عربي — ✅. I3 الإظهار لا يتبع ردّ `setForeground` — ⚠️ بنيوي فقط. I4 إشعار نتيجة (`9046`) لا يُلغي المستمر (`9043`) — ⚠️. I5 عطل الإشعار لا يُلغي النقل (`runCatching` في `post`) — ⚠️.
**Metamorphic:** M1 determinism — ✅ `sameErrorAlwaysGivesSameText`. M2 ضجيج تقني زائد لا يغيّر المخرج — ✅ `addingTechnicalNoiseDoesNotChangeUserText`. M3 ترتيب الطابور لا يغيّر سطر النتيجة — ✅ (اختبارات `outcome*` تبني طوابير مختلفة).

**ترتيب المخاطرة (I × L × (6−D)) وما اختُبر أولًا:** F4 = 150، F8 = 100، F1 = 36 ⇒ بُدئ بالمعجم كله ثم بترتيب النشر قبل خدمة الإبقاء، ثم إشعار النتيجة.

---

## 5) تصريح ما لا يمكن التحقق منه محليًا (نموذج §4.5.5)

> **لا يمكن تشغيل الاختبارات محليًا لأن:** المشروع بلا Robolectric وبلا حزمة اختبار instrumented، فمسارات `Context`/`NotificationManagerCompat`/`WorkManager`/`HttpURLConnection` لا تُشغَّل على JVM خالص. **الثقة:** متوسطة-عالية — المنطق الكامل مُختبَر على JVM، ومسار العرض مُتحقَّق منه بنيويًا (نص الشيفرة + الرموز داخل `classes.dex` + أن نص التسريب القديم صار **صفرًا** في الشحنة). **ما يلزم:** `adb install` على جهاز بأندرويد 12/13/14 ثم: (1) رفع > 30 ثانية + قطع الشبكة من الإعدادات السريعة ⇒ يجب أن يظهر في الإشعار «تعذّر الوصول إلى خادم السحابة…» لا `UnknownHostException`، و`adb logcat -s CloudTransferWorker:* CloudflareR2:*` يحمل الرقم الخام؛ (2) الضغط على Home أثناء الرفع ⇒ إشعار التقدّم يستمر في التحديث؛ (3) بعد تعذّر ملف ⇒ heads-up «تعذّر N في طابور النقل…» مع زرّي الإيقاف المؤقت/الإلغاء شغالين من شاشة القفل.

**ما لم يُنفَّذ صراحةً (بلا تلميع):** استكمال الرفع من منتصف **الملف** يستلزم multipart upload غير الموجود في `CloudflareR2Client` — الاستئناف الحالي على مستوى **العنصر** (ما رُفع يُتخطى بالمقارنة الموجودة مسبقًا) والتنزيل على مستوى **البايت** عبر `Range`؛ ونسخ/تكرار الملف في السحابة يحتاج `copyObject` موقّعًا.
