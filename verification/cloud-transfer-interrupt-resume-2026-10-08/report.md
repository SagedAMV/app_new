# زرّ لا يقطع، وتنزيل يعيد من الصفر — تشخيص وإصلاح (2026-10-08، الجولة الثانية)

## 1) ما شكا منه المستخدم
1. «أضغط استئناف أو توقف فلا يعمل، كأنها الأزرار فقط».
2. «إذا انقطع الإنترنت لا يحمي آخر سحب أو رفع، بل يقطّع ويعيد تنزيل الملف من جديد».

## 2) الجذور — بأسطر فعلية من الكود قبل التعديل
| # | الدليل | النتيجة |
|---|---|---|
| J1 | `CloudSyncManager.kt:246` `fun cancelDownloads() { downloadJob?.cancel() }`، وكان `pauseTransfers()` في الـViewModel يرفع راية `paused` فقط | لا شيء يقطع العنصر الجاري؛ الراية تُقرأ **بين** الدفعتين (`nextPending`) ⇒ عنصرٌ طويل = أزرار بلا أثر |
| J2 | العامل كان يبني إشعاره وحده، وعند خروجه `clearProgress()` يُلغيه | بعد «إيقاف مؤقت» يختفي الزرّ نفسه؛ فلا استئناف من خارج التطبيق |
| J3 | `CloudflareR2Client.downloadFile`: `FileOutputStream(targetFile)` بلا `Range`، و`finally { if (!completed) targetFile.delete() }` | كل انقطاع يمحُو الجزء ⇒ إعادة من الصفر (شكوى 2 حرفيًا) |
| J4 | `CloudSyncManager.kt:402` `File.createTempFile("selected_", ".part", staging)` + `finally { temp.delete() }` (يُنفَّذ حتى مع `CancellationException`) | الاسم عشوائي فلا يُعثر على الجزء أصلًا، والمدير يحذفه أيضًا |
| J5 | `CloudSyncManager.kt:1087` `listFiles()?.forEach { it.delete() }` عند أول عملية في كل عملية تشغيل | أي جزء نجا من الانقطاع يُمحى عند إعادة تشغيل التطبيق |
| J6 | `CloudUploadConfirmationSheet.kt:46` `enabled = isOnline && !transfer.active && …` | زرّ «رفع المحدد إلى السحابة» **يكون معطَّلًا أثناء أي نقل جارٍ** — وهو الجزء الثاني من «الأزرار فقط»، ومع الطابور لا مبرر له |

## 3) الإصلاح
- **مقاطعة حقيقية**: `CloudTransferControls` (جديد) — `pause()` = رفع الراية **+** `manager.cancelDownloads()` **+** إشعار «النقل موقوف مؤقتًا / استئناف» يبقى بعد خروج العامل؛ `resume()` يُلغيه ويعيد الجدولة؛ `cancelQueued()` يُلغي ما لم يبدأ ويقطع الجاري. المستقبل الإشعاري والواجهة **يستدعيان نفس الدوال** (طبيعة تطبيق.md §11 «لا تكرر منطق العمل») فلا مسار ناقص للإشعار.
- **العامل** يفسّر `CancellationException` (مردودةً في `Result` أو رميًا) كـ«إيقاف بإرادة المستخدم»: يبقى العنصر `PENDING` **بلا استهلاك محاولة** (`classify` في `CloudTransferWorker.kt`).
- **استكمال التنزيل**: `CloudflareR2Client.downloadFile(..., resume = true)` يرسل `Range: bytes=N-` ويلحق عند الردّ `206`؛ وعند ردّ الخادم `200` (تجاهل النطاق) يُصفَّر ويبدأ من جديد — لا خلط جزئيّ بكامل. البصمة تُحسب على الكل: يُمرَّر الجزء السابق عبر `MessageDigest` قبل اللصق (`updateFromFile`)، فتبقى مقارنة `sha256` مع الفهرس صحيحة.
- **حفظ الجزء**: `CloudDownloadPart` (كائن نقي جديد) يسمّي الملف `dl_<مفتاح مُنظَّف>_<بصمة قصيرة>.part` (بمنع `/` و`\` و`..` — المفتاح نص بعيد)، ويرفض الاستكمال إذا كان الجزء ≥ الحجم المعلن أو الحجم مجهولًا. المدير والعميل لم يعودا يحذفان الجزء عند `CancellationException` (`keepPart`/`keepPartial`)، والتظيف صار «القديم فقط» (`isStale` بحد 24 ساعة) في J5.
- **الورقة المحلية**: إزالة `!transfer.active` من زر الرفع، و«اتصل بالإنترنت لبدء الرفع» صارت «لا إنترنت الآن — سيُسجَّل الطلب ويبدأ تلقائيًا عند عودة الاتصال»، والرفع يصير ممكنًا أوفلاين (الطابور يُسلّمه عند عودتها). وحذف `cancelCloudDownloads` مع مستدعيه (لا دالة بلا مستدعٍ)، والبطاقة تُعرض في الورقة نفسها بحالتها وأزرارها.

## 4) البوابات
```
compileReleaseFullKotlin  G1_OK  1m20s  (وبعده 13s مترسّبًا)
testReleaseFullUnitTest   G2_OK  37s → suites=21 tests=248 skipped=3 failures=0 errors=0
   CloudTransferQueueLogicTest tests=23 failures=0 · CloudDownloadPartTest tests=10 failures=0
lintVitalReleaseFull      G3_OK  57s
assembleReleaseFull       G4_OK  2m5s · EXIT=0
```
أخطاء طريقي (قُيِّدت ثم صُححت، لا تُخفى): `Conflicting declarations` لتكرار `val snapshot`؛ كسر مستدعٍ ثانٍ للبطاقة لم أكن قرأته (`CloudUploadConfirmationSheet.kt:43`)؛ `assertTrue(boolean, String)` بتوقيع معكوس؛ و`./gradlew: Permission denied` مرّتين لأن اللقطة تسلب بت التنفيذ، و`JAVA_HOME is set to an invalid directory` ثلاث مرات لأن `.toolchain` يُمحى بين الاستدعاءات — عولِج بملف `/home/user/run-gates.sh` يثبّت الأدوات ثم يشغّل البوابات في جلسة واحدة.

**تحقق في الحزمة الموقّعة (لا في المصدر):** `classes*.dex`: `CloudDownloadPart`=11، `CloudTransferControls`=22، `CloudTransferNotifier`=3، `rearmAction`=1، بادئة الجزء `dl_`=1، و`bytes=`=1؛ والنصوص `استئناف / النقل موقوف مؤقتًا / لا إنترنت الآن — سيُسجَّل الطلب / سيُستأنف من حيث توقف / أُوقف النقل` كلها `True`. `zipalign: سليم`، `apksigner: التوقيع سليم`.

## 5) ما لم يُحلّ (صراحةً)
- **الرفع لا يُستكمل من منتصف البايتات**: R2/S3 يتطلب `multipart upload` (create/upload-part/complete/abort + حفظ ETags الأجزاء) — `CloudflareR2Client` لا يملكه (عامةً: `uploadFile/uploadText/downloadFile/listBucketObjects/deleteObject/sha256Hex`). ما يُضمَن الآن: لا يُعاد رفع ملف **اكتمل** (روابط الفهرس + البصمة)، والملف الذي انقطع في منتصفه يُعاد رفعه وحده.
- **لم يُجرَّب على جهاز**: استئناف `Range` على R2 الحقيقي (ردّ 206 مقابل 200)، وسلوك الزرّ من شاشة القفل، وحذف النظام الجزئي تحت ضغط التخزين. المنطق مُغطًّى باختبارات JVM والحذف/اللصق لا يُختبران هنا بلا Robolectric.
