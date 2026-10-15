# وجهة الرفع داخل السحابة (targeted upload) — 2026-10-15

## الطلب
من قائمة القبول المتبقّية: «اسحب/ارفع ملفات إلى **مجلد سحابي محدّد**». كان الرفع يذهب دائمًا إلى
جذر السحابة ببنية المجلدات المحلية؛ لا حقل وجهة في `CloudUploadPlan` ولا في `CloudTransferBatch`
(`destination` كان للتنزيل فقط)، ولا وسيلة عند المستخدم لاختيار المكان وقت الطلب.

## الافتراضات القاتلة وما تحقّق منها
| # | الافتراض | الدليل | الاختبار الأرخص |
|---|---|---|---|
| A1 | الوجهة يجب أن تُجمَّد مع الدفعة لا أن تُقرأ وقت التنفيذ | `runUpload` يبني خطته من `batch.*` بعد دقائق (أو بعد إعادة تشغيل العملية) | `targetSurvivesJsonRoundTrip…` |
| A2 | مفتاح المجلد قد يموت بين الاختيار والبدء | `moveRemoteFiles` يتحقق من القائمة وقت التنفيذ لا وقت الواجهة | `unknownFolderIsRefusedWithAHumanReason` |
| A3 | الترميز القديم يجب أن يُقرأ «لا وجهة» | طوابير محفوظة على أجهزة قبل التحديث؛ `JSONObject.isNull` يعيد true للحقل الغائب | `queueJsonWrittenBeforeThisFeatureStillDecodesAsNoTarget` |
| A4 | تغيير توقيع `@Composable` عام يكسر مستدعيًا مخفيًا | درس مستفاد من جولة سابقة (`CloudTransferProgressCard`) | وسائط جديدة **بقيم افتراضية** + `grep` مستدعين: واحد فقط (`FilesScreen.kt:639`) |
| A5 | فرْد الملفات مقابل حفظ الشجرة قرار مستخدم لا تفصيل داخلي | «ارفعهم إلى هذا المجلد» تعني مباشرة؛ تفريغ مجلدات مختارة إلى وجهة يهرس تنظيمه | `filesAreFlattenedIntoTargetFoldersKeepTheirTree` |

## السيناريوهات (3 عادي / 3 حدّي / 2 فشل متوقّع / 2 عدائي)
| ID | نوع | السيناريو · الخطوات · النتيجة المتوقّعة · Oracle | I | L | D | Risk |
|---|---|---|---|---|---|---|
| T1 | عادي | تحديد 4 ملفات → «رفع للسحابة» → اختيار «الصور» → تأكيد · **Oracle:** `plan.destinationLabel` يذكر «الصور»، والدفعة تحمل `targetFolderKey=folder:x` + الاسم | 4 | 3 | 3 | 36 |
| T2 | عادي | عدم اختيار شيء → السلوك القديم حرفيًا (جذر + شجرة) · **Oracle:** `targetFolderKey == null` و`labelFor == "إلى جذر السحابة مع بنية المجلدات"` | 4 | 2 | 4 | 16 |
| T3 | عادي | رفع مجلد محلي بعمقين إلى وجهة → الجذر يستبدل أباه والعمق يبقى تحته · **Oracle:** `parentFor(treeRoot=true,…)==target` و`false` يبقي `derivedParent` | 5 | 3 | 3 | 45 |
| T4 | حدّي | انقطاع شبكة في المنتصف → الاستئناف بنفس الوجهة · **Oracle:** `dedupeKey` يتضمّن الوجهة، والاسم المُجمَّد لا يُعاد اشتقاقه | 5 | 4 | 2 | **150** |
| T5 | حدّي | حذف المجلد من جهاز آخر قبل بدء الدفعة · **Oracle:** `validate` يعيد رسالة إنسانية والدفعة تُعلَّم بلا استهلاك محاولة (`Defer` لاحقًا) | 4 | 3 | 3 | 36 |
| T6 | حدّي | نفس الملفات إلى وجهتين في انتظار واحد · **Oracle:** دفعتان مستقلتان (`sameFilesToDifferentFoldersAreDifferentRequests`) | 4 | 3 | 3 | 36 |
| T7 | فشل متوقّع | مفتاح `legacy:` أو بادئة فارغة `folder:` · **Oracle:** مرفوض برسالة عربية، لا «مجلد غامض» | 4 | 3 | 3 | 36 |
| T8 | فشل متوقّع | حقل وجهة فارغ/مسافات في JSON · **Oracle:** يُقرأ `null` لا مجلدًا باسم فارغ | 3 | 3 | 3 | 27 |
| T9 | عدائي | نص طابور تالف (كُتب نصفه وسقطت العملية) · **Oracle:** طابور فارغ بلا استثناء عند الإقلاع | 4 | 2 | 3 | 24 |
| T10 | عدائي | تسريب المفتاح الداخلي في نص المستخدم · **Oracle:** لا `folder:`/`path:`/مقطع UUID في أي `labelFor` | 5 | 2 | 4 | 40 |

**Invariants:** I1 الوجهة لا تُشتق من قائمة حيّة عند العرض (تُجمَّد). I2 `null` = السلوك القديم حرفيًا. I3 لا مفتاح داخلي في نص مستخدم. I4 الوجهة جزء من بصمة منع التكرار. I5 JSON أقدم = لا وجهة، لا فشل.
**Metamorphic:** M1 نفس الدخل ⇒ نفس السطر (`sameChoiceAlwaysProducesTheSameLabel`). M2 فراغات حول المفتاح لا تغيّر هويته (`targetIsTrimmedSoPaddedKeysDoNotSplitIdentity`). M3 إضافة/إزالة مجلدات مختارة تقلب `flattens` وحدها دون لمس بقية الخطة.
**Traceability:** T1..T3,T7,T10 ↔ `CloudUploadTargetTest` (8) · T4,T6 ↔ `sameFilesToDifferentFolders…` · T5 ↔ `unknownFolderIsRefused…` · T8,T9 ↔ `blankTargetFields…`, `unreadableQueueText…` · I5 ↔ `queueJsonWrittenBeforeThisFeature…` — وكلها تُشغَّل محليًا.

## ما نُفِّذ
* `data/cloud/CloudUploadTarget.kt`: سياسة صافية (normalize / isSelectable / validate / flattens / parentFor / labelFor) — قرار واحد تستعمله الشبكة والواجهة والإشعار.
* `CloudUploadPlan` ← `targetFolderKey` + `targetFolderName` (مجمَّدين وقت الاختيار) + `destinationLabel`.
* `CloudTransferBatch` ← نفس الحقلين، **ودخول الوجهة في `dedupeKey`**، وترميز JSON رجعي (`JSONObject.NULL` + `takeIf { isNotBlank() }`).
* `CloudSyncManager.prepareUploadPlan(…, targetFolderKey, targetFolderName)`، وفي `uploadSelected`: تحقّق من الوجهة على قائمة الفحص الحالية عند التنفيذ، ثم إما **فرْد** الملفات داخلها (حين لا مجلدات مختارة) أو **إعادة جذر الشجرة** تحتها مع بقاء العمق — وبلا إنشاء أوصاف مجلدات أو روابط محلية كاذبة في وضع الفرْد.
* `CloudTransferWorker.runUpload` يمرّر وجهة الدفعة، فالتنفيذ بعد الاستئناف يطابق ما اختاره المستخدم.
* بطاقة تأكيد الرفع: صفّ `FilterChip` أفقي (`جذر السحابة` + مجلدات `folder:`/`path:`) وسطر `plan.destinationLabel` بدل النص الثابت «سيُرفع المحدد فقط مع بنية المجلدات».
* `FilesViewModel`: وجهة محفوظة في الـVM (تبقى عبر المعاينات)، `setUploadTarget(key, name)`، وتنظيف مع `closeUploadPlan()`، ورسالة القبول تذكر الوجهة.

## البوابات — على رأس البعيد الحقيقي `a29e0e8` (لا على أساس قديم)
أُجريت بعد `git reset --hard FETCH_HEAD` ثم `git apply -3` لفرقي وحده (11 ملفًا): اللقطة كانت قد
أرجعت الشجرة إلى ما قبل refactor Appwrite، فأي تشغيل على الأساس القديم لا يُحتسب دليلًا.

| بوابة | النتيجة |
|---|---|
| `compileReleaseFullKotlin` | `EXIT=0` |
| `compileReleaseFullUnitTestKotlin` + `testReleaseFullUnitTest` | `EXIT=0` — **19 حزمة، 179 اختبارًا، 0 فشل، 0 خطأ**؛ ملفات النتائج بطابع 12:27 والساعة 12:34، فليست UP-TO-DATE |
| `lintVitalReleaseFull` | `EXIT=0` |
| `assembleReleaseFull` | `EXIT=0` |

شُغِّلت بـ`/home/user/g.sh` على `-Xmx704m -XX:MaxMetaspaceSize=384m --max-workers=1`: الجهاز 1.9
غيغابايت، ومعه `-Xmx832m` الافتراضي كان الـdaemon يُقتل فتُقرأ النتيجة «daemon disappeared» لا خطأ
شيفرة — وهذا بعينه سبب سقوط البوابات في المحاولتين الأوليين، لا تعديلي.

**عطلان واقعيان ظهرا في الطريق، وكلاهما مُصلَح:**
1. اختباري `corruptedTargetFieldsAreReadAsNoTargetNotAsACrash` سقط بـ`NoSuchElementException` لأن
   **دخل الاختبار نفسه** كان JSON غير صالح (ربطت السلاسل فأنتج نصًا مرفوضًا ⇒ طابور فارغ ⇒
   `.single()`). صُحّح الدخل إلى `targetFolderKey":""`، والحادث صار حالة مقصودة:
   `unreadableQueueTextBecomesAnEmptyQueueNotAsACrash`.
2. `AppwriteStorageTransportTest.kt:220` عندكم **لا يُترجَم** على `a29e0e8`: باني
   `AppwriteStorageTransport` آخر وسائطه `platformHeaders: Map`، فتمرير lambda خلفية غير قانوني —
   وكان ذلك يُسقط حزمة اختبارات JVM كلها (فلا اختبار واحد يُشغَّل). استُبدل بوسيط مُسمّى
   `openConnection = { … }`: 15 اختبارًا أخضر بدل لا ترجمة.

فحص نصي: لا محرف صيني/ياباني/كوري/عبري في الملفات المعدّلة ولا في رسالة الكوميت.


## تصريح ما لا يمكن التحقق منه محليًا
**لا يمكن تشغيل الاختبارات محليًا لأن:** المشروع بلا Robolectric/instrumentation، فمسار `ModalBottomSheet`
والـ`FilterChip` و`DataStore` و`CloudSyncManager` الشبكي لا يعمل على JVM خالص. **الثقة:** متوسطة-عالية —
السياسة كلها مختبَرة على JVM، والتجميع وlint والشحنة خضراء، والتسريب الوحيد الممكن (مفتاح داخلي في نص)
مغطّى بخاصية. **ما يلزم على جهاز:** (1) رفع 3 ملفات مع اختيار مجلد «الصور» ⇒ يجب أن تظهر داخله على
الخادم وتظهر «إلى «الصور» مباشرة» في البطاقة والإشعار؛ (2) حذف المجلد من جهاز آخر ثم بدء الطابور ⇒
رسالة «مجلد الوجهة لم يعد في قائمة السحابة» وبقاء الدفعة في الانتظار لا فشل نهائي؛ (3) تحديث من نسخة
قديمة مع طابور محفوظ ⇒ يُكمّل إلى الجذر بلا انهيار عند الإقلاع.

## لم يُنفَّذ (معلن، لا مُضمَر)
زر «أضف ملفات إلى هذا المجلد» **داخل شاشة السحابة** نفسه: يتطلب منتقي مستندات + `fileRepository` داخل
`CloudFilesViewModel` (الشاشة لا تملك اليوم أي مسار استيراد محلي — تحقّق: `grep CloudTransferKind.UPLOAD`
في `CloudFilesViewModel.kt` أعاد صفرًا)، فهو جولة مستقلة. ونسخ/تكرار ملف في السحابة تغيّر تخطيطه بعد
انتقال المشروع من R2 إلى `AppwriteStorageRepository`، فـ«توقيع copyObject لـS3» لم يعد هو الإجراء.
