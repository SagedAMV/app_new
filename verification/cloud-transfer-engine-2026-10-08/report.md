# عطل «قيد الانتظار ولا يتغيّر أبدًا» في طابور السحابة — التشخيص والإصلاح

**التاريخ:** 2026-10-08 · **الفرع:** `main` · **البناء:** `assembleReleaseFull` (النسخة الكاملة)

## 1) ما رآه المستخدم
رفع ملفات ← إطفاء الشبكة ← تشغيلها ⇒ كل العناصر تبقى «قيد الانتظار»، لا تتقدّم ولا تكمل.

## 2) الجذر (بأسطر فعلية، لا استنتاج)
1. **لا أحد يعيد تسليح الطابور عند عودة الشبكة.** المستمع الوحيد لتغيّر الشبكة هو
   `CloudSyncManager.kt:133-140`:
   ```kotlin
   if (checkIsOnline() && !wasOnline) scope.launch {
       val settings = preferences.snapshot()
       if (settings.autoSyncEnabled && settings.isConfigured) {
           if (foreground) syncWithServer() else scheduler.enqueueSyncWhenConnected()
       }
   }
   ```
   يُعيد جدولة **المزامنة** فقط، ولا يذكر `cloud_r2_transfers`.
2. **نتيجتي كانت تُهمَل.** عند سقوط شرط `NetworkType.CONNECTED` يوقف WorkManager العامل؛
   والنتيجة المردودة من عامل موقوف (`Result.retry()`) **لا تُؤخذ بها**، فلا جولة قادمة.
3. **`ExistingWorkPolicy.APPEND` لا يُحيي سلسلة متروكة في حالة إلغاء** (ورميته كان قد
   يرفع `IllegalStateException` إلى المستدعي) ⇒ لا طريق عودة إلا بفعل يدوي جديد.
4. **`Result.retry()` أداة خاطئة لانتظار شبكة:** تأخيره أُسّي (30s→60→…→حتى 3 ساعات)،
   فلا «استئناف عند عودة الاتصال» فعليًا حتى في الحالات التي يعمل فيها.
5. **الواجهة كاذبة بالسكوت:** `lastError` كان يُعرض للفاشلة فقط، فلا يعرف المعلَّق أنه ينتظر شبكة.

## 3) الإصلاح
| ملف | التغيير |
|---|---|
| `CloudSyncManager.kt` | بعد الانتقال offline→online: `scheduler.enqueueTransfers()` **خارج** بوابة `autoSyncEnabled` (كل دفعة طلب صريح من المستخدم، فعودتها معلّقة بالشبكة وحدها). |
| `CloudSyncScheduler.kt` | `APPEND` ← **`APPEND_OR_REPLACE`** + `runCatching` (يُحيي السلسلة الملغاة، ولا يرمي إلى المستدعي). |
| `CloudTransferQueue.kt` | قرار نقي جديد: `rearmAction(snapshot, networkAvailable, workerStopped, busyEncountered) → NONE \| WAIT_FOR_NETWORK \| RETRY_SOON`. |
| `CloudTransferWorker.kt` | حقن `CloudSyncScheduler`؛ تفصيل `Busy` عن `Defer` (انشغال القفل = شبكة سليمة ⇒ backoff مناسب؛ انقطاع = تسليح مقيّد بالشبكة)؛ الخروج يُترجَم عبر `rearmAction` فلا يعتمد على نتيجة تُهمَل. |
| `CloudFilesViewModel.kt` | `init` يُسلّح الطابور إن وُجد معلَّق ولم يوقفه المستخدم (خطاف النظر إلى الحالة)، و`createFolder(name, parentKey)`. |
| `CloudFilesScreen.kt` | «مجلد جديد» في شريط المجلد، وصفّان في قائمة المجلد: «إنشاء مجلد فرعي هنا» و«تنزيل المجلد إلى الجهاز»؛ والبطاقة تعرض «بانتظار عودة الشبكة — سيستأنف النظام من حيث توقف دون إعادة ما اكتمل» و«سبب الانتظار: …» لكل دفعة معلّقة. |

## 4) خصائص مرنة في السحابة (ما أُنجز هذه الجولة)
- **إنشاء مجلد سحابي** (`createRemoteFolder`) بنفس نمط المفاتيح القائم `"folder:<UUID>"` (المصدر:
  `CloudSyncManager.kt:792` في مسار الرفع) — لا مفتاح مبتكرًا يكسره `foldersFromManifest`.
  التحقق صريح: اسم غير فارغ وبلا `/`، أب من نوع `folder:` فقط، ورفض التكرار في المستوى نفسه.
- **تنزيل المجلد من قائمته** بدل الشريط فقط (إعادة استعمال `CloudDownloadDestinationDialog` القائم).
- **إعادة التسمية / تغيير المسار / الحذف** كانت موجودة وتبقى؛ والتعليقات التوضيحية لأسباب المنع
  تُترك كما هي (سلوك مقصود لا خلل).

## 5) ما لم يُضَف (بلا تزييف للتغطية)
- **نسخ/تكرار ملف داخل السحابة**: يتطلب `copyObject` موقّعًا (`PUT` بـ`x-amz-copy-source`)؛
  `CloudflareR2Client` لا يملكه ولا مساعد طلب موقّع عام يُعاد استعماله (عامةً: `uploadFile`,
  `uploadText`, `downloadText`, `downloadFile`, `listBucketObjects`, `deleteObject`, `sha256Hex`) —
  إضافته تستوجب اختبار توقيع مستقلًا، فلا تُرتجل في جولة إصلاح عطل.
- **رفع من شاشة السحابة إلى مجلد سحابة محدّد**: `uploadSelected` يشتق موضع الملفات من روابط
  المجلد المحلي (`describeFolder` + `folderKeys`)؛ تجاوز ذلك يحتاج `destinationRemoteKey` مع
  إعادة النظر في قواعد الدمج `CloudUploadMergeRules.findExistingFile` (تطابق الاسم داخل الوجهة)
  حتى لا تتحول إعادة استخدام إلى نقل صامت. يُنفَّذ كحلقة مستقلة باختبارها.
- **اختيار ملفات المكتبة داخل السحابة** (ورفع صورة/تسجيل صوتي من الشاشة نفسها): واجهة مُنتقِي
  جديدة فوق `FileRepository`/`capture`، لا سطر توصيل.

## 6) الأدلة الخام
```
compileReleaseFullKotlin   → G1_OK  BUILD SUCCESSFUL in 1m 26s
testReleaseFullUnitTest    → G2_OK  BUILD SUCCESSFUL in 1m 13s
   suites=20 tests=238 skipped=3 failures=0 errors=0     (قبل الإصلاح: 234)
   CloudTransferQueueLogicTest: tests=23 failures=0 errors=0
lintVitalReleaseFull       → G3_OK
assembleReleaseFull        → G4_OK  EXIT=0
```
اختبارات جديدة لقرار إعادة التسليح (4) تغطي: انقطاع ⇒ `WAIT_FOR_NETWORK`؛ عامل موقوف ⇒
`WAIT_FOR_NETWORK` (لأن نتيجته تُهمَل)؛ انشغال ⇒ `RETRY_SOON`؛ موقوف مؤقتًا أو طابور فارغ ⇒ `NONE`.

**خلل واحد حدث أثناء الطريق وقُيّد:** `Unresolved reference 'deferred'` في `CloudTransferWorker.kt:61` —
أعدت تسمية رايات الحلقة ونسيت تفريع «انقطع الاتصال»؛ صُحّح بحذف الراية (القرار النهائي يقيس
الشبكة بنفسه) لا بإسكات المترجِم.
