# تقرير جلسة — إصلاح فشل استيراد النسخ الاحتياطية (نفاد ذاكرة على الجهاز)

**التاريخ:** 2026-10-06 · **النطاق:** `data/backup` + `data/storage` · **الإصدار:** 1.3.1 (code 5)
**الطلب:** استعادة «نسخة احتياطية قديمة» على جهاز بعد تثبيت النسخة الأخيرة تُفشل الاستيراد.
**الرسالة المرصودة من المستخدم (نص الصورة):**

> `فشل الاستيراد: Failed to allocate a 35258384 byte allocation with 9560560 free bytes and 9336KB until OOM, target footprint 268435456, growth limit 268435456`

---

## 1) التصنيف ومعايير النجاح

**التصنيف:** عطل أداء/موارد (OOM) في مسار استيراد — أصلحه هيكلي (تدفّق + قرص مؤقت)، لا زيادة حجم الكومة.

| # | معيار قابل للفحص | نتيجة |
|---|---|---|
| SC1 | استيراد نسخة بحجم ~33.6MB (موضع الفشل) لا يحتاج تخصيصًا بحجم الملف | ✅ لم يعد هناك `readBytes()` للملف (0 مواضع) |
| SC2 | محتوى الملفات المضمّنة لا يُجمَّع في الذاكرة قبل الاستعادة | ✅ `Map<String, File>` على قرص مؤقت بدل `Map<String, ByteArray>` |
| SC3 | توافق كامل مع النسخ القديمة (بلا شفرة) والجديدة (بسطر الشفرة) ومع JSON القديم | ✅ 11 اختبار JVM + 3 مسارات مفحوصة |
| SC4 | لا فقدان لبيانات المستخدم الحالية عند فشل الاستيراد | ✅ `clearAll()` ما زالت بعد التحقق من الأرشيف، والفشل يسبقها |
| SC5 | رسالة الفشل مفهومة للمستخدم بدل نص VM تقني | ✅ `FRIENDLY_IMPORT_FAILURE` + التفاصيل في `Log.e` |
| SC6 | بوابات المشروع خضراء ثم نسخة كاملة موقَّعة | ✅ compile → tests → **lintVital** → assembleReleaseFull |

---

## 2) السبب الجذري (مقاس، لا مُخمَّن)

ثلاث طبقات استهلاك كانت تتراكب في لحظة واحدة داخل `BackupRepository.import`:

| # | المصدر | الكمية |
|---|---|---|
| 1 | `openInputStream(uri).readBytes()` | حجم الملف كاملًا، مع نموّ `ByteArrayOutputStream` بالمضاعفة ⇒ ذروة ≈ **2×** |
| 2 | `BackupSignature.stripIfPresent(bytes)` | **نسخة كاملة أخرى** لإسقاط سطر الشفرة (~50 بايت تُكلّف 33.6MB) |
| 3 | `importFromZip`: `HashMap<String, ByteArray>` | **كل** ملفات الأرشيف مفكوكة في الذاكرة قبل أي كتابة، بحد `MAX_ENTRY_BYTES = 200MB` |

النتيجة: `growth limit 268435456` = 256MB ممتلئة، فالتخصيص التالي بحجم الملف (35,258,384) فشل. الفشل كان **قبل** أي مسح للبيانات — لم تفقد بياناتك، فقط لم تتم الاستعادة.

**ملاحظة مقارنة مهمة:** مسار **التصدير** كان يكتب `disk.inputStream().use { it.copyTo(zip) }` (تدفقي) — لذلك نجح التصدير وانكسر الاستيراد. هذا يفسّر لماذا وُجدت النسخة أصلاً.

## 3) الإصلاح — تدفّق وقرص مؤقت

**Data Flow بعد التعديل:**

```
URI ──openFileDescriptor──▶ statSize > MAX_BACKUP_BYTES؟ ──نعم──▶ رسالة عربية (قبل القراءة)
   └─openInputStream─▶ BufferedInputStream(32KB)
        ├─ BackupStream.skipSignatureLine(header)     // mark/reset — بلا نسخة بايتات
        ├─ BackupStream.looksLikeZip()                // 4 بايتات ثم تُعاد
        ├─ ZIP   ▶ BackupStream.spool(...)            // 8KB/قطع، كل ملف → filesDir/backup_import_<ts>/N
        │           manifest فقط في الذاكرة
        │           ثم parseAndValidateManifest → fileStorage.clearAll()
        │           ثم FileStorage.importTempFile(...) // rename داخل نفس النظام، بلا RAM
        └─ JSON  ▶ reader(UTF_8).readText() → parse → apply   // نسخة قديمة بياناتها وصفية
   finally: spoolDir.deleteRecursively()
```

**Invariants (محافظ عليها / مُضافة):**
- Inv-1: لا `clearAll()` قبل قراءة الأرشيف والتحقق من `manifest` ⇒ أرشيف ناقص لا يمسح المكتبة.
- Inv-2: لا تخصيص بحجم الملف أو بحجم عنصر في الكومة (الحد الأقصى للقطعة 8KB).
- Inv-3: حماية «قنبلة الضغط» باقية: `copyBounded(maxBytes = MAX_ENTRY_BYTES)` بنفس رسالة الخطأ العربية القديمة.
- Inv-4: القرص المؤقت داخل `filesDir/backup_import_*` — لا تلمسه `clearAll()` (تحذف `filesDir/library` فقط، مُتحقق من `FileStorage.kt:255`)، والانتقال منه `renameTo` لا نسخ.
- Inv-5: المجلدات والعناصر خارج `files/` لا تُقرأ إطلاقًا (كانت تُقرأ ثم تُرمى).

**Metamorphic:** تغيير حجم ملف داخل المكتبة (1KB ← 40MB) لا يغيّر نتيجة الاستيراد ولا ذروة الذاكرة؛ ووضع/عدم سطر الشفرة لا يغيّر محتوى `manifest` المستخرج.

---

## 4) السيناريوهات (10 بتوزيع إلزامي 3/3/2/2) — مختصرة بالحقول المطلوبة

| ID | النوع · Persona/State · Steps (مقتطف) · Inputs · Outputs · **Oracle** · Failure Modes (3) | I | L | D | **Risk** |
|----|---|---|---|---|---|
| S1 | عادي · طالب يرجّع نسخة 33MB فيها 6 ملفات محاضرات · يفتح النسخ ← استعادة ← يختار الملف ← يؤكد ← انتظار ←list. **Oracle:** كل سجل يُستعاد + حجم كل ملف على القرص = حجمه في الأرشيف، ولا `OOM`. | OOM=لا، تعداد=6 | 5 | 5 | 2 | **50** |
| S2 | عادي · نسخة حديثة بسطر الشفرة `::UNIHUB_BACKUP::` · **Oracle:** `skipSignatureLine=true` ثم `looksLikeZip=true` ومسار الأرشيف (يغطيه اختبار `headerAndMagic…`) | 4 | 5 | 3 | **40** |
| S3 | عادي · نسخة JSON قديمة (بيانات وصفية فقط) · **Oracle:** تُقرأ كنص واحد فقط وتُستعاد الجداول/المهام | 4 | 3 | 3 | **36** |
| S4 | حدّي · عنصر داخل الأرشيف أكبر من `MAX_ENTRY_BYTES` · **Oracle:** `IOException` بالرسالة العربية نفسها، ولم تُمس المكتبة | 5 | 2 | 4 | **20** |
| S5 | حدّي · مزوّد URI لا يبلّغ عن الحجم (`statSize = -1`) · **Oracle:** لا يفشل الاستيراد؛ الحماية لكل عنصر قائمة | 4 | 2 | 3 | **24** |
| S6 | حدّي · أرشيف فيه مجلدات `files/` وعنصر `notes/x.txt` · **Oracle:** 0 ملف مؤقت لهما (اختبار `spool_createsSpoolDir…`) | 3 | 4 | 4 | **24** |
| S7 | فشل متوقع · أرشيف بلا `manifest.json` · **Oracle:** «النسخة الاحتياطية لا تحتوي على بيانات صالحة» + لا `clearAll()` | 5 | 3 | 4 | **30** |
| S8 | فشل متوقع · فشل كتابة على القرص (تخزين ممتلئ) · **Oracle:** رسالة عربية مختصرة للمستخدم، والسجل فيه السبب التقني؛ القرص المؤقت يُمسح في `finally` | 4 | 2 | 3 | **24** |
| S9 | عدائي · «قنبلة ضغط» 1KB تضغط إلى 3GB · **Oracle:** تتوقف عند أول تجاوز للحد (اختبار `spool_stopsAtFirstEntryOverTheLimit`) | 5 | 2 | 2 | **40** |
| S10 | عدائي · ملف ليس نسخة إطلاقًا (نص عشوائي/ثنائي) · **Oracle:** «الملف ليس نسخة احتياطية صالحة» ولا تغيير في القاعدة | 4 | 3 | 4 | **24** |

**أعلى 3 عُولج أولًا:** S1 (الحالة المرصودة) → S2/S9 (حرفية الرأس + حدود التدفق) → S7 (عدم لمس بيانات المستخدم).

---

## 5) الاختبارات المحلية + المصفوفة + Runbook

**جديد:** `app/src/test/java/com/unihub/app/data/backup/BackupStreamTest.kt` — 11 اختبارًا على JVM خالص (بلا Context/Android)، تبني أرشيف ZIP حقيقيًا بـ`ZipOutputStream` وتفكّه.

| Scenario | اختبارات | Oracle |
|---|---|---|
| S2, S3 | `skipSignatureLine_consumesHeaderOnlyWhenItMatches`, `…_leavesStreamUntouchedForLegacyBackup` | السطر يُستهلك عند التطابق فقط، ولا بايت يضيع للنسخة القديمة (`assertArrayEquals` على التدفق) |
| S1, S6 | `looksLikeZip_detectsArchiveAndDoesNotConsumeMagicBytes`, `…_returnsFalseForJsonAndForTinyFiles` | توقيع `PK\x03\x04` يُقرأ ويُعاد؛ JSON/قصير/فارغ ⇒ false |
| S1, S2 | `headerAndMagicTogetherRouteARealExportToTheArchivePath` | السلسلة كاملة: شفرة → أرشيف → `manifest.app == "unihub"` |
| S4, S9 | `copyBounded_copiesEverythingUnderTheLimit`, `copyBounded_rejectsPayloadBiggerThanLimitWithUserReadableMessage` | 1MB سليمة؛ عند التجاوز `IOException` ونصها العربي حرفيًا |
| S1 | `spool_writesFileEntriesToDiskAndKeepsOnlyManifestInMemory` | عنصر **12MB** ينتهي ملفًا على القرص بحجمه ومحتواه، وعنصر خارج `files/` لا يُستعاد |
| S6 | `spool_createsSpoolDirAndSkipsDirectoryEntries` | مجلد داخل الأرشيف ⇒ 0 ملف مؤقت، والمجلد العميق يُنشأ |
| S7 | `spool_reportsMissingManifestForNonBackupArchive` | `manifestText == null` بلا استثناء |
| S4 | `spool_stopsAtFirstEntryOverTheLimit` | يتوقف عند الحد برسالة مفهومة |

```bash
export JAVA_HOME=/home/user/.toolchain/toolchain/jdk17 ANDROID_HOME=/home/user/.toolchain/sdk \
       GRADLE_USER_HOME=/home/user/.toolchain/gradle-home
./build-release-full.sh          # compile → testReleaseFullUnitTest → lintVitalReleaseFull → assembleReleaseFull
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testReleaseFullUnitTest/*.xml      # التحقق من XML لا من «SUCCESSFUL» وحده
```

**النتائج الخام (من غلاف النسخة الكاملة نفسه):**

| البوابة | النتيجة |
|---|---|
| `compileReleaseFullKotlin` | BUILD SUCCESSFUL in 15s |
| `testReleaseFullUnitTest` | `TOTAL tests=215 skipped=3 failures=0 errors=0` (BackupStream **11/11**، ملاحظات 29/29 + 4/4) |
| `lintVitalReleaseFull` | BUILD SUCCESSFUL in 1m 5s |
| `assembleReleaseFull` | `app-releaseFull.apk` 14M · `zipalign: سليم` · `apksigner: التوقيع سليم` |

**فحص داخل الـAPK الناتج (`classes*.dex`) — لا اعتماد على «نجح البناء»:** `backup_import_` = 1، رسالة الفشل العربية = 1، رسالة تجاوز العنصر = 1، تحذير التخطي = 1، **`importBytes` = 0** (الدالة اليتيمة زالت فعلًا).

**سجل فشل مُصلَّح لا مُخفى:** أول تشغيل للاختبارات أعطى `tests=215 failures=1` — عيب في **اختباري** (قرأت JSON من موضع بداية الأرشيف بدل استخراج `manifest.json`)؛ صُحح الاختبار ليغطي المسار الحقيقي وأعيدت البوابات حتى الخضرة الكاملة.

---

## 6) التنظيف المرتبط بالتغيير (طلب «الدوال الميتة»)

| العنصر | الحالة |
|---|---|
| `FileStorage.importBytes` | حُذفت — صار يتيمة بسبب هذا التغيير؛ الاستعادة تمر بـ`importTempFile` الموجودة (وثيقة الصنف حُدِّثت) |
| `BackupSignature.stripIfPresent` | حُذيت — التخطي صار على التدفق. `startsWithSignature` بقيت (مستعملة في `ShareReceiverActivity:86`) |
| `BackupRepository.isZipArchive`, `ZipInputStream.readBounded` | حُذفتا — منطقهما انتقل إلى `BackupStream` |
| استيرادات `ByteArrayInputStream/ByteArrayOutputStream/ZipInputStream` | أُزيلت بعد أن صارت بلا استعمال |
| **لم تُمَس (خارج النطاق، مسجَّلة):** 6 دوال `Table*` في `NoteWorkspaceOperations` بلا مستدعٍ إنتاجي، و`moveBlock`/`moveIdeaCard` (اختبارات فقط) | قرار موثق في تقرير `notes-writing-flow-2026-10-06` §7-H2 |

**Blast Radius:** Direct = `BackupStream.kt` (جديد) + `BackupRepository.import*` + وثائق `FileStorage`. Indirect = `BackupViewModel:87` و`ShareReceiverActivity:145` — يستدعيان `import(uri)` الذي **لم تتغير توقيعه** ⇒ لا تعديل لديهما. Must remain unchanged = صيغة الأرشيف/`SCHEMA_VERSION=3`/التصدير/مسار JSON القديم/تراتبية `clearAll()` — كلها مخُتبرة أو لم تُمَس. Unknown = سلوك التدفق على مزوّدي ملفات خارج `MediaStore`/SAF (انظر §8-U1).

**Consequence Check (ما نتج عنه فعلًا):** نقل الاستيراد إلى قرص مؤقت يثير سؤالين أُلزما بتنفيذ: (أ) أين يعيش المؤقت بحيث لا تمسحه `clearAll()` ولا يُنسخ نسخًا ⇒ `filesDir/backup_import_*` + `importTempFile` (rename). (ب) كيف يُنظَّف عند الفشل النصفي بعد `clearAll()` ⇒ `finally { deleteRecursively() }`. وأُلغي اقتراحان بعد الفحص المضاد: `android:largeHeap` (يؤجل لا يعالج) و`ZipFile` على ملف حقيقي (يتطلب ملفًا كاملاً على القرص ويكسر تدفق SAF).

---

## 7) ثقوب التغطية (3)

- **H1 — لا اختبار على جهاز/ContentProvider حقيقي.** `importFrom` (_URI_ + `statSize` + SAF) يحتاج Android؛ المُغطى هو الطبقة القرصية/القرارات. **العلاج:** فصل المنطق إلى `BackupStream` بلا Android هو ما جعل 11 اختبارًا ممكنًا؛ وأرخص تحقق إضافي: استعادة النسخة نفسها على الجهاز (30 ثانية) — **وهو المطلوب من المستخدم الآن**.
- **H2 — قرص مؤقت = مساحة تخزين.** نسخة 1.5GB قد تتجاوز المساحة المتاحة؛ الخطأ سيظهر برسالة عربية ويُمسح المؤقت، لكن لم تُضف pre-flight check للمساحة (يتطلب تقدير فك الضغط — غير دقيق). **مراقبة مبكرة:** سطر `Log.e("فشل الاستيراد")` مع `IOException` من `FileOutputStream`.
- **H3 — مسار JSON القديم ما زال يبني نصًا كاملاً + `JSONObject`.** مقبول (نسخ وصفية فقط، عادة أقل من بضع MB)، ولو ظهرت نسخة JSON ضخمة فالخطوة التالية هي `JsonReader` تدريجي بدل `JSONObject(String)`. مسجَّل كـ C (Improvement) لا تنفيذه الآن: بناءه يغيّر 8 دوال تحليل بلا دليل كسر.

## 8) تصريحات رسمية

- **U1 — لا يمكن التحقق خارجيًا من استعادة فعلية على جهاز المستخدم** | الثقة: **عالية على المنطق، متوسطة على الجهاز** | السبب: لا ADB/emulator في هذه البيئة؛ ما تحققت منه: البوابات + 215 اختبارًا + محتويات الـdex | ما يلزم: تثبيت `UniHub-v1.3.1-releaseFull.apk` ثم «استعادة نسخة محلية» على نفس الملف الذي فشل.
- **U2 — لم أغيّر حدود `MAX_BACKUP_BYTES (1500MB)` ولا `MAX_ENTRY_BYTES (200MB)`** لأن رفعها بلا سبب يغيّر سياسة الأمان؛ الفشل لم يكن من الحدود بل من طريقة القراءة.
- **U3 — النسخة المسلَّمة كاملة غير مصغَّرة** بطلب المستخدم: `assembleReleaseFull` (`isMinifyEnabled=false`)، بينما النسخة المصغَّرة 3.1M؛ الناتج الكامل 14M، موقَّع بمفتاح `keystore/unihub-release.jks` و`apksigner verify` سليم.
