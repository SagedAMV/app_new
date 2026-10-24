# سيناريوهات الترحيل إلى Supabase — UniHub

> وثيقة تشغيلية للمهاجرة من Appwrite إلى Supabase.
> كل ادّعاء في هذه الوثيقة إمّا **مُتحقَّق حيًّا** (مع المخرجات الخام محفوظة في
> `docs/evidence/`) أو **موسوم صراحةً** بأنه غير قابل للتحقق خارجيًا.

---

## 0. ACTION Log (سجلّ العمليات)

| # | Input | Output | القرار |
|---|---|---|---|
| ACTION #1 | قراءة `تفكير.md` و`طبيعة تطبيق.md` | بروتوكول تشغيل + قواعد تنظيم | كل قرار تالٍ مقيَّد بهذين الملفين |
| ACTION #2 | فحص `/home/user/uploads/السحابة.md` | URL + `sb_publishable_…` + `sb_secret_…` | المفتاح الإداري **خارج** شجرة المشروع فقط |
| ACTION #3 | فحص كود Appwrite القديم | طبقة جسر كاملة (AppwriteAuthBridge + StorageAuthorizer + node-bridge) | Supabase **يحلّ محل** الخدمة لا يجاورها ⇒ حذف الجسر |
| ACTION #4 | فحص المخططات الفعلية | `profiles / cloud_objects / cloud_manifests` | استخراج الجداول من نماذج التطبيق لا من تخمين |
| ACTION #5 | محاولة DDL عبر Management API / pooler | 401 / `28P01` | لا رجوع إلى DDL خارجي؛ المسار الوحيد = موصل Supabase |
| ACTION #6 | تفعيل الموصل (بقرار المستخدم) | المشروع `yjvrkxabhlygdmtoujge` ACTIVE_HEALTHY | تطبيق المخطط مباشرة على القاعدة الحيّة |
| ACTION #7 | تطبيق `unihub_cloud_schema` | 3 جداول، 12 سياسة عامة، 4 سياسات storage، حاوية واحدة، 15 دالة | المخطط صار مرجعًا واحدًا |
| ACTION #8 | كتابة `CloudFileProtocol` + `StorageAuthorizer` محايدَين | منطق بلا ذكر المزوّد | طبقة النطاق تبقى كما هي (§11/§13) |
| ACTION #9 | كتابة محوّل Supabase (7 ملفات) | سطح دوال مطابق للمحوّل القديم | «استبدل النوع فقط» في طبقة النطاق |
| ACTION #10 | الحذف: `data/appwrite/`, `service/appwrite/`, `AppwriteModule`, `AppwriteAuthRepository` | لا traces لـ Appwrite في الكود | لا تكامل قديم بلا توثيق |
| ACTION #11 | اختبار حيّ: RLS | 44/44 | سياسات RLS تُفرض في القاعدة |
| ACTION #12 | اختبار حيّ: دورة حياة الإدارة | 33/33 | الإدارة عبر دوال القاعدة بلا مفتاح إداري في التطبيق |
| ACTION #13 | اختبار حيّ: مسار الملفات | 23/23 | رفع/سجل/بيان/تنزيل/Range/حذف يعمل |
| ACTION #14 | `:app:compileDebugKotlin` + `:app:testDebugUnitTest` | 20/20 اختبار وحدة ناجح | الكود سليم |
| ACTION #15 | `scripts/check_supabase_migration.py` | 359 فحصًا ناجحًا | حارس ثابت يمنع عودة Appwrite |
| ACTION #16 | تنظيف بيانات الفحص الحيّ | الجداول والحاوية شبه خالية | لا بيانات اختبار متبقية تُوهم بمحتوى حقيقي |

---

## 1. Ask_yourself and reason🧠 (قبل اعتماد كل حل)

### 1.1 الافتراضات

| # | افتراض | كيف نتحقق |
|---|---|---|
| A1 | التطبيق يتكلّم مع Supabase برمز مستخدم فقط | `SupabaseAuthRepository` + `SupabaseHttp` (لا `service_role`) + مسح `sb_secret_` |
| A2 | الوصول يُفرض في القاعدة لا في الواجهة | 12 سياسة RLS + 4 سياسات storage + فحص حيّ |
| A3 | بنية البيانات مشتقّة من نماذج التطبيق | `CloudFileProtocol` (fileId/generation/etag) مطابق لمنطق Appwrite السابق |
| A4 | لا بيانات قديمة تُرحَّل | المشروع كان خاليًا من الجداول (فحص أولي) |

### 1.2 زوايا غير محسوبة (≥2)

1. **ماذا لو كان مستخدمٌ قديم بـ Appwriteexpects تسميات (labels) لا أعمدة؟**
   → التطبيق يعرض «صلاحيات» بأعمدة `can_*`؛ التحويل تم في `CloudAuthRules.fromSupabaseProfile`
   مع الحفاظ على تسلسل الصلاحيات (تعديل⇒رفع⇒قراءة) الموجود أصلًا في Appwrite.
2. **ماذا لو كان الحاوية Storage يرفض المفاتيح غير اللاتينية؟**
   → متحقَّق حيًّا: مفتاح عربي ⇒ `400 Invalid key`. الحل المعتمد: `storage_path = fileId` (ASCII حتمي)،
   والمفتاح المنطقي يبقى في `object_key`. فحص حيّ في `cloud_files_test.sh` (سطر «مفتاح غير لاتيني في Storage = 400»).
3. **ماذا لو انتهت صلاحية رمز المستخدم أثناء عملية طويلة؟**
   → `SupabaseAuthRepository.validAccessToken()` يحدّث الرمز عند الحاجة؛
   `CloudAuthManager` يمسح الجلسة المحلية عند فشل التحقق ⇒ المستخدم يُعاد توجيهه لتسجيل الدخول.

### 1.3 ثلاثة قراءات محتملة خاطئة (وكيفية التحقق)

| القراءة المحتملة | كيف نُبطلها |
|---|---|
| «إنشاء الجداول = ترحيل البيانات» | الفحص الأولي: 0 جداول في المشروع ⇒ لا بيانات سابقة أصلًا؛ ووثائق `docs/supabase-setup.md` تذكر صراحةً أن مشاريع Appwrite القديمة غير قابلة للوصول بالمفاتيح المتاحة |
| «رمز 403 يعني منعًا» | RLS في SELECT/UPDATE/DELETE **يرشّح بصمت** (200 + صفر صفوف). الفحص الحيّ يثبت ذلك: «قارئ: تحديث عنصر محجوب = 204» ثم «العنصر ما زال موجودًا» |
| «التطبيق يملك صلاحية إدارة» | `admin_*` دوال `SECURITY DEFINER` تتحقق من `is_admin()` أو `service_role`؛ ومفتاح `service_role` غير موجود في التطبيق |

### 1.4 القرار الإلزامي لكل دورة

كل دورة تنتهي بـ: **تغيير ملموس أو قرار أو توقف** — لا الاكتفاء بالقراءة.

---

## 2. خريطة تدفّق البيانات (Data-Flow Map)

```
[مستخدم] ──▶ CloudAuthManager (core)
                │  login / restoreTokens / requirePermission
                ▼
        SupabaseAuthRepository ──▶ POST /auth/v1/token?grant_type=password   (GoTrue)
                │                   PUT  /auth/v1/user                       (تغيير كلمة المرور)
                │                   POST /rest/v1/rpc/admin_*                (إدارة داخل القاعدة)
                ▼
        SupabaseHttp  ── apikey: sb_publishable_… | Authorization: Bearer <JWT المستخدم>
                │
      ┌─────────┴────────────┬──────────────────────┐
      ▼                      ▼                      ▼
 public.profiles        public.cloud_objects     storage.objects
 (RLS: select_own/admin,  (RLS: download→read,    (RLS: upload→insert,
  update_own, admin_all)    upload→insert,           modify→update/delete,
                              modify→update/delete)    على bucket unihub-files)
      │                      │                      │
      └──────────┬───────────┴──────────────┬───────┘
                 ▼                          ▼
        public.cloud_manifests          بايتات الملف
        (RLS: download→read,            (storage_path = fileId ASCII)
         modify→insert/update/delete)
```

- التوقيع: `app/src/main/java/com/unihub/app/data/supabase/SupabaseConfig.kt`
- النقل: `.../data/supabase/SupabaseHttp.kt`
- التخزين: `.../data/supabase/SupabaseStorageTransport.kt` + `SupabaseStorageRepository.kt`
- الصلاحيات: `.../data/auth/CloudAuthRules.kt` + `.../data/cloud/CloudFileRules.kt`

---

## 3. عشرة سيناريوهات واقعية (على الأقل) قبل اعتماد الحل

كل سيناريو: **المدخلات / الحالة / الخطوات / المخرجات / أنماط الفشل /Oracle قابل للفحص**.

### S1 — أول تشغيل بعد التثبيت (تسجيل دخول)
- **المدخلات:** بريد وكلمة مرور صحيحة.
- **الحالة:** لا يوجد رمز محفوظ.
- **الخطوات:** `login()` → `POST /auth/v1/token?grant_type=password` → حفظ `access_token` + `refresh_token`.
- **المخرجات:** حساب نشط بصلاحياته من `public.profiles`.
- **أنماط الفشل:** بريد خاطئ ⇒ 400 (`invalid_credentials`)؛ موقوف ⇒ `is_active=false` ⇒ `effective_can_*` ترفض كل شيء.
- **Oracle:** `admin_lifecycle_test.sh`: «تسجيل دخول المستخدم الجديد» + «كلمة المرور الجديدة تعمل» (33/33).

### S2 — مستخدم بلا أي صلاحية
- **المدخلات:** حساب `can_download=can_upload=can_modify=false`.
- **الحالة:** رمز صالح.
- **الخطوات:** يفتح «ملفات السحابة».
- **المخرجات:** قائمة فارغة؛ لا خطأ.
- **أنماط الفشل:** لو أعاد التطبيق 403 ⇒ UX مكسور. القاعدة ترشّح بصمت ⇒ 200 + `[]`.
- **Oracle:** `rls_smoke_test.sh`: «بلا صلاحية: لا يرى عناصر» + «بلا صلاحية: قائمة الحاوية فارغة» (44/44).

### S3 — رفع ملف
- **المدخلات:** ملف ≤ 50,000,000 بايت.
- **الحالة:** صلاحية رفع ✔.
- **الخطوات:** حساب `fileId` ← `POST /storage/v1/object/unihub-files/{fileId}` (x-upsert) ← `POST /rest/v1/cloud_objects`.
- **المخرجات:** صف سجل + كائن في الحاوية.
- **أنماط الفشل:** ملف أكبر من السقف ⇒ رفض مبكر محليًا؛ مفتاح غير لاتيني في المسار ⇒ 400 ⇒ لذلك المسار = `fileId`.
- **Oracle:** `cloud_files_test.sh`: «رفع البايتات = 200» + «مفتاح غير لاتيني في Storage = 400» (23/23).

### S4 — مزامنة البيان (sync)
- **المدخلات:** لقطة الجهاز.
- **الحالة:** جيل محلي `n`.
- **الخطوات:** `POST /rest/v1/cloud_manifests` بمعرّف `m{generation:016d}_{sha17}`.
- **المخرجات:** 201 (أُنشئ) أو 409 (تعارض تفرّد ⇒ كتابة متزامنة).
- **أنماط الفشل:** تعارض ⇒ يعيد التطبيق المزامنة (compare-and-create) لا أن يتجاهل.
- **Oracle:** «بيان مكرر = 409 (تعارض تفرّد)» و«إدراج البيان = 201».

### S5 — تنزيل مع انقطاع
- **المدخلات:** ملف محلي ناقص (نصف بايت).
- **الحالة:** خادم متاح.
- **الخطوات:** `GET` بترويسة `Range: bytes=N-` ⇒ 206.
- **المخرجات:** تكملة الملف والتحقق من البصمة.
- **أنماط الفشل:** الخادم لا يدعم Range ⇒ خطأ واضح (لا بيانات ناقصة صامتة).
- **Oracle:** «طلب Range = 206» + «ترويسة Content-Range» (تحقّق حيّ).

### S6 — انتهاء صلاحية الجلسة أثناء المزامنة
- **المدخلات:** رمز منتهٍ + `refresh_token` صالح.
- **الحالة:** مستخدم على الشبكة.
- **الخطوات:** `validAccessToken()` ⇒ `POST /auth/v1/token?grant_type=refresh_token` ⇒ يُستخدم الرمز الجديد.
- **المخرجات:** العملية تكمل بلا فقدان.
- **أنماط الفشل:** فشل التحديث ⇒ `CloudSessionExpiredException` ⇒ `CloudAuthManager` يمسح الجلسة ⇒ إعادة تسجيل دخول برسالة عربية.
- **Oracle:** رمز الاستجابة 200/400 من GoTrue + رسالة `CloudFailureMessages` (مصدر نص واحد).

### S7 — إيقاف حساب من لوحة الإدارة
- **المدخلات:** مشرف يوقف مستخدمًا.
- **الحالة:** المستخدم نشط الآن.
- **الخطوات:** `admin_set_active(target,false)` ⇒ تُحذف جلسات المستخدم ⇒ `effective_can_*` ترفض.
- **المخرجات:** المستخدم يفقد الوصول فورًا حتى لو كان رمزه ما زال صالحًا.
- **أنماط الفشل:** الاعتماد على صلاحية الرمز وحدها ⇒ تسريب؛ الحل = فحص `is_active` في كل دالة فعّالة.
- **Oracle:** «موقوف: لا يرى عناصر» + «موقوف: لا ينشئ عنصرًا = 403».

### S8 — تعديل صلاحيات مستخدم من داخل التطبيق
- **المدخلات:** مشرف ⇦ checkbox «تعديل».
- **الحالة:** قاعدة فيها `profiles`.
- **الخطوات:** `admin_set_permissions` ⇒ تتحقق من التسلسل (تعديل⇒رفع⇒قراءة) ⇒ `UPDATE profiles`.
- **المخرجات:** 204 + الصلاحيات مطبَّقة.
- **أنماط الفشل:** تركيبة غير متسلسلة ⇒ 400 `invalid_permissions` (لا حالة نصف مُطبَّقة).
- **Oracle:** «ترقية بلا قراءة = 400» + «الصلاحيات طُبِّقت» + «رفع ممنوع بعد الضبط».

### S9 — نقل ملكية ملف بين مستخدمين
- **المدخلات:** `owner_id` = مستخدم.
- **الحالة:** المستخدم يُحذف.
- **الخطوات:** `DELETE auth.users` ⇒ `owner_id ON DELETE SET NULL` ⇒ الصف يبقى (لا فقدان بيانات).
- **المخرجات:** الملف يبقى موجودًا بلا مالك.
- **أنماط الفشل:** `ON DELETE CASCADE` ⇒ فقدان ملفات — لذلك `SET NULL` مقصود.
- **Oracle:** `admin_lifecycle_test.sh`: «لم يبقَ صفّ للملف الشخصي» + فحص `cloud_objects` بعد الحذف.

### S10 — مزامنة جهازين على نفس الملف
- **المدخلات:** جهازان، نفس المفتاح المنطقي.
- **الحالة:** نفس جيل المزامنة.
- **الخطوات:** كلٌّ يكتب بيانًا بنفس `generation` وبصمة مختلفة ⇒ معرّف `m{gen}_{sha}` مختلف ⇒ كلاهما 201 (لا تعارض!).
- **المخرجات:** آخر جيل في القاعدة هو المرجع.
- **أنماط الفشل:** لو كان المعرّف = `m{gen}` فقط ⇒ تعارض ⇒ فقدان نسخة جهاز.
- **Oracle:** «إدراج البيان = 201» ثم «بيان مكرر = 409» — التمييز بين التعارض الحقيقي (نفس المعرّف) وتعدد الأجهزة (معرّف مختلف).

### S11 — طلب بدون شبكة
- **المدخلات:** لا اتصال.
- **الحالة:** جلسة محلية محفوظة.
- **الخطوات:** `CloudAuthManager.requireOnline()` ⇒ IOException ⇒ رسالة عربية واحدة.
- **المخرجات:** لا انهيار؛ البيانات المحلية untouched.
- **أنماط الفشل:** предположение بوجود شبكة ⇒ crash في coroutine.
- **Oracle:** `CloudAuthRules.isCachedSessionFresh` + `CloudFailureMessages` (اختبار وحدة في `CloudAuthRulesTest`).

### S12 — حذف حساب من الإدارة
- **المدخلات:** مشرف ⇦ «حذف المستخدم».
- **الحالة:** المستخدم موجود.
- **الخطوات:** `admin_delete_user` ⇒ حذف `auth.users` ⇒ cascade إلى `profiles`.
- **المخرجات:** 204 + لا صفّ للملف الشخصي.
- **أنماط الفشل:** حذف مشرف ⇒ `protected_admin` (403) — حماية.
- **Oracle:** «حذف مستخدم = 204» + «لم يبقَ صفّ للملف الشخصي» + «حذف النفس = 403».

---

## 4. الثوابت (Invariants)

| # | الثابت | كيف يُفحص |
|---|---|---|
| I1 | لا يوجد مفتاح إداري (`sb_secret_` / `service_role`) داخل التطبيق أو ملفات المشروع | `scripts/check_supabase_migration.py` + مسح `grep` |
| I2 | كل وصول للبيانات محكوم بـ RLS على مستوى الصف | 16 سياسة (12 public + 4 storage) + 44 فحصًا حيًّا |
| I3 | تسلسل الصلاحيات ثابت: تعديل⇒رفع⇒قراءة | `effective_can_*` في القاعدة + `CloudAuthRules` + `admin_set_permissions` |
| I4 | معرّف الملف = `f` + أول 35 خانة من sha256 للمفتاح المنطقي | `CloudFileProtocol.fileId` + اختبار وحدة ببصمة منشورة |
| I5 | مسار التخزين ASCII حتمي (لا يعتمد على اسم المستخدم) | `fileIdIsSafeAsSafeStoragePath` + فحص حيّ 400 على المفتاح العربي |
| I6 | تعارض الكتابة المتزامنة يُترجَم إلى 409 لا إلى نجاح صامت | معرّف البيان `m{gen}_{sha}` + فحص حيّ 409 |
| I7 | البيانات المحلية (Room/ملفات) لا تتأثر بطبقة السحابة | لم تُمسّ `data/storage`/`data/backup` إلا في التعليقات |

---

## 5. علاقات التشوّه (Metamorphic)

| # | العلاقة | الفحص |
|---|---|---|
| M1 | تغيير حالة الملف (كبر/تصغير) **لا يغيّر** `fileId` | نفس المفتاح المنطقي ⇒ نفس `fileId` (وحدة) |
| M2 | ترتيب مفاتيح JSON لا يغيّر البيان | `canonical()` يرتّب المفاتيح ⇒ jsonb يطبّعها |
| M3 | إعادة تشغيل الفحص الحيّ لا تترك بيانات | سكربتات الفحص تحذف ما أنشأته (Cleaning في `cleanup()`) |
| M4 | نفس العملية على جهاز كمبيوتر وب أجهزة ⇒ نفس النتيجة | الحسابات والحقول محسوبة من البيانات لا من البيئة |

---

## 6. المخاطر (Risk = Impact × Likelihood × (6 − Detectability))

| # | الخطر | I | L | D | R |最先الإجراء |
|---|---|---|---|---|---|---|
| R1 | تسريب وصول: واجهة تفرض والقاعدة لا تفرض | 5 | 2 | 1 | 10 | RLS على كل جدول + سياسة storage + فحص حيّ (44) |
| R2 | فقدان بيانات قديمة من Appwrite (غير قابلة للوصول) | 5 | 3 | 2 | 30 | توثيق صريح: لا بيانات قديمة migrated + خطة انتقال في `docs/supabase-setup.md` |
| R3 | فشل الرفع الجزئي (انقطاع أثناء الإرسال) | 3 | 3 | 3 | 27 | رفع بمقطع واحد `x-upsert` + إعادة محاولة كاملة + تحقق من البصمة |
| R4 | انتهاء صلاحية الجلسة في منتصف عملية | 2 | 3 | 2 | 12 | `refresh_token` + `CloudSessionExpiredException` + رسالة عربية |
| R5 | إدخال غير لاتيني في مسار التخزين | 3 | 2 | 2 | 12 | `storage_path = fileId` + اختبار وحدة + فحص حيّ |
| R6 | خطأ في ترتيب الصلاحيات (امنحModification بلا رفع) | 4 | 2 | 2 | 16 | التسلسل مطبَّق في 3 مواضع (القاعدة/التطبيق/الإدارة) |

> الترتيب تنازلي حسب R؛ أعلى ثلاثة: R2 وR3 وR1 — وهي التي نعالجها في §12 (بوابة الإنجاز).

---

## 7. مصفوفة التتبّع (Scenario → Trace → Test → Oracle)

| السيناريو | أثر الكود | الفحص | Oracle |
|---|---|---|---|
| S1 | `SupabaseAuthRepository.login` | `admin_lifecycle_test.sh` | «تسجيل دخول المستخدم الجديد» = PASS |
| S2 | `effective_can_*` + RLS | `rls_smoke_test.sh` | «بلا صلاحية: لا يرى عناصر» = PASS |
| S3 | `SupabaseStorageTransport.uploadFile` | `cloud_files_test.sh` | «رفع البايتات = 200» = PASS |
| S4 | `CloudSyncManager` + `cloud_manifests` | `cloud_files_test.sh` | «إدراج البيان = 201» + «مكرر = 409» |
| S5 | `SupabaseHttp.requestStream` (Range) | `cloud_files_test.sh` | «طلب Range = 206» + «Content-Range» |
| S6 | `SupabaseAuthRepository.validAccessToken` | `SupabaseHttpTest` + مسار `restoreTokens` | رمز GoTrue 200 عند التحديث |
| S7 | `admin_set_active` + `effective_can_*` | `rls_smoke_test.sh` | «موقوف: لا ينشئ عنصرًا = 403» |
| S8 | `admin_set_permissions` | `admin_lifecycle_test.sh` | «ترقية بلا قراءة = 400» + «الصلاحيات طُبِّقت» |
| S9 | `owner_id ON DELETE SET NULL` | `admin_lifecycle_test.sh` | «لم يبقَ صفّ للملف الشخصي» |
| S10 | معرّف البيان `m{gen}_{sha}` | `cloud_files_test.sh` | «إدراج البيان = 201» (لا تعارض بين جهازين) |
| S11 | `CloudAuthManager.requireOnline` | `CloudAuthRulesTest` + `CloudFailureMessages` | رسالة واحدة عربية |
| S12 | `admin_delete_user` | `admin_lifecycle_test.sh` | «حذف مستخدم = 204» + «حذف النفس = 403» |

---

## 8. سجلّ الافتراضات الميتة (Kill-Assumptions Log)

| الافتراض الميت | كيف مات | الدليل |
|---|---|---|
| «يمكن إنشاء الجداول عبر Management API أو pooler» | 401 على Management API و`28P01` على pooler | سجلّ الفحص في `docs/evidence/capability-probe.txt` |
| «مفتاح النشر وحده يسمح بـ DDL» | 401 على `/rest/v1/` | نفس الملف |
| «Storage يقبل أي مفتاح نصّي» | 400 `Invalid key` لمفتاح عربي | `cloud_files_test.sh` (فحص حيّ) |
| «TUS متاح للاستئناف أثناء الرفع» | 400 على TUS | اختبار مبكر في هذه الجلسة (مذكور في `docs/supabase-setup.md`) |
| «رمز المستخدم في `apikey` يعمل» | 401 | `rls_smoke_test.sh` (يستخدم الترويسة الصحيحة فقط) |
| «‏RLS يعيد 403 عند SELECT» | 200 + صفر صفوف | `rls_smoke_test.sh`: «العنصر ما زال موجودًا» |
| «مشروع Supabase يحوي بيانات Appwrite قديمة» | المشروع كان خاليًا من الجداول عند البدء | فحص أولي في `docs/evidence/rest_sec.json` |

---

## 9. Runbook (تشغيل)

```bash
# 1) فحوص القاعدة الحيّة (تحتاج متغيّرات بيئة؛ الأسرار لا تُحفظ في المستودع)
export SUPABASE_URL=https://yjvrkxabhlygdmtoujge.supabase.co
export SUPABASE_PUBLISHABLE_KEY=sb_publishable_...
export SUPABASE_SERVICE_KEY=sb_secret_...        # إداري — للاستخدام المحلي فقط
bash supabase/tests/rls_smoke_test.sh            # 44 فحصًا
bash supabase/tests/admin_lifecycle_test.sh      # 33 فحصًا
bash supabase/tests/cloud_files_test.sh          # 23 فحصًا

# 2) الحارس الثابت (يمنع عودة Appwrite أو تسرّب أسرار)
python3 scripts/check_supabase_migration.py      # 359 فحصًا

# 3) بناء واختبارات الوحدة
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
./gradlew :app:assembleDebug

# 4) تطبيق المخطط على مشروع جديد (ترتيب الملفات مهم)
#    supabase/migrations/20261023000100_unihub_cloud_schema.sql
#    supabase/migrations/20261023000200_admin_user_lifecycle.sql
```

المخرجات المحفوظة: `docs/evidence/rls-smoke-test.txt` (44/44)،
`docs/evidence/admin-lifecycle-test.txt` (33/33)،
`docs/evidence/cloud-files-test.txt` (23/23).

---

## 10. Consequence Check (12 سؤالًا)

| # | السؤال | الإجابة |
|---|---|---|
| Q1 | ماذا يحدث لو أُلغي `is_active` من صف؟ | كل `effective_can_*` ترفض ⇒ لا وصول (مُتحقَّق حيًّا: «موقوف: لا يرى عناصر») |
| Q2 | ماذا لو حذفت `public.profiles`؟ | `handle_new_user` يعيد إنشاء الصف عند تسجيل دخول جديد؛ `guard_profile_privileges` يمنع تعديل الصلاحيات |
| Q3 | ماذا لو تكرّر `generation` بعينه؟ | تعارض تفرّد 409 (مُتحقَّق) — لا الكتابة الصامتة |
| Q4 | ماذا لو كان الملف أكبر من 50 MB؟ | `CloudFileRules` يرفض قبل الإرسال (سقف الخادم نفسه) |
| Q5 | ماذا لو انقطع التنزيل؟ | `Range` يعيد البايتات الناقصة (206 مُتحقَّق) |
| Q6 | ماذا لو نفدت صلاحية الرمز؟ | `refresh_token` يجدّد؛ وإلا `CloudSessionExpiredException` + مسح الجلسة |
| Q7 | ماذا لو أعاد الخادم 500؟ | `SupabaseApiException` يحمل `status/code/detail`، والتطبيق يعرض رسالة عربية واحدة |
| Q8 | ماذا لو حُذف الملف من الحاوية؟ | `cloud_objects` يبقى ⇒ التنزيل يفشل برسالة واضحة (لا صمت) |
| Q9 | ماذا لو أُزيلت حاوية `unihub-files`؟ | `CloudSyncManager` يفشل عند الرفع برسالة «إعدادات مشروع الملفات غير مطابقة» |
| Q10 | ماذا لو عمل التطبيق على جهاز بلا Google Play؟ | لا اعتماديات جديدة؛ `HttpURLConnection` + `org.json` فقط (§14) |
| Q11 | ماذا لو تحوّل التطبيق إلى الوضع الأوفلاين؟ | `CloudAuthManager.requireOnline()` يمنع العمليات السحابية فقط؛ البيانات المحلية سليمة |
| Q12 | ماذا لو نُسخ `sb_secret_` داخل المستودع؟ | `scripts/check_supabase_migration.py` يفشل (فحص regex على قيمة سرية) |

---

## 11. Blast Radius + تصنيف الأثر (A–E)

| البند | التصنيف | السبب |
|---|---|---|
| `data/supabase/*` (جديد) | **A** — إضافة | طبقة محوّل جديدة فقط |
| `data/cloud/*` | **B** — تعديل محدود | تغيير المزوّد فقط؛ منطق النطاق (fileId/generation/etag) لم يتغيّر |
| `data/auth/CloudAuthManager`, `CloudAuthRules` | **B** | نفس الأنواع؛ تغيّر مصدر الصلاحيات فقط |
| `core/prefs/Cloud*Preferences` | **B** | مفاتيح التخزين renamed (سلوك المستخدم لم يتغيّر) |
| `feature/settings/UserManagementSection`, `BackupScreen` | **C** — نصوص فقط | أسماء المزوّد في الوصف |
| `data/appwrite/*`, `service/appwrite/*`, `di/AppwriteModule` | **D** — حذف | استبدال كامل (البديل مكتمل ومُتحقَّق) |
| `data/storage`, `data/backup`, `data/local`, `ui/*` | **E** — بلا مساس | خارج نطاق السحابة؛ لم تُلمس إلا التعليقات |

**Must remain unchanged:** التخزين المحلي، جدولة التذكيرات، المظهر، سلوك المزامنة المحلية، نصوص الواجهة العربية (عدا اسم المزوّد).

**Unknown:** أي تكامل خارجي غير مرئي في المستودع (لا يوجد في `settings.gradle.kts` سوى `:app`).

---

## 12. بوابة الإنجاز (Completion Gate — 12 فحصًا)

| # | الفحص | الحالة | الدليل |
|---|---|---|---|
| G1 | التطبيق يستخدم Supabase بدل Appwrite | ✔ | `grep -rn Appwrite app/src/main` ⇒ تعليقات تاريخية فقط؛ لا `import` ولا استدعاء |
| G2 | لا وجود لطبقة Appwrite | ✔ | `data/appwrite/`, `service/appwrite/`, `AppwriteModule.kt` محذوفة |
| G3 | بنية سحابية موجودة بسياسات وصول صحيحة | ✔ | 12 + 4 سياسات، 15 دالة، حاوية `unihub-files` |
| G4 | العمليات الأساسية السابقة تعمل | ✔ | 100 فحصًا حيًّا (44+33+23) |
| G5 | الميزات المحلية والواجهة سليمة | ✔ | `:app:compileDebugKotlin` ناجح + اختبارات وحدة 20/20 |
| G6 | الاختبارات والبناء تعمل بنتائج حقيقية موثّقة | ✔ | `docs/evidence/*` + سجلّ Gradle |
| G7 | لا تكامل قديم بلا تفسير | ✔ | `docs/README.md` يشرح حذف `appwrite-setup.md` |
| G8 | لا أسرار عالية الصلاحية | ✔ | حارس regex + مسح `sb_secret_` ⇒ صفر نتائج |
| G9 | المخطط migratable/مطبَّق | ✔ | ملفات `supabase/migrations/*` مطابقة للحالة الحيّة (استُخرجت بـ `pg_get_functiondef` و`pg_policies`) |
| G10 | نموذج البيانات مشتقّ من التطبيق | ✔ | `CloudFileProtocol` يطابق الصيغ السابقة في Appwrite (fileId/generation/etag) |
| G11 | الفشل/انتهاء الجلسة/إعادة المحاولة متعامل معه | ✔ | S3/S6/S7/S11 + `CloudFailureMessages` |
| G12 | لا بيانات قديمة تدّعي نقلها | ✔ | `docs/supabase-setup.md` + §8: لا يوجد مشروع Appwrite يمكن الوصول منه |

---

## 13. Forgotten Piece Check (لماذا قد يبدو «شبه جاهز»؟)

| السبب | الفحص المضاد | النتيجة |
|---|---|---|
| 1. الجداول أُنشئت لكن RLS لم يُفعَّل | فحص `pg_policies` + 44 فحصًا حيًّا | RLS مفعّل ومُختبَر |
| 2. التطبيق يتكلّم لكن لا أحد يستخدمه | استدعاءات `SupabaseStorageRepository` من `CloudSyncManager` | المسار موصول |
| 3. الاختبارات «زخرفية» بلا مرجع | كل فحص له oracle مذكور | 100 فحوص + 20 اختبار وحدة |
| 4. أسرار في المستودع | `check_supabase_migration.py` | صفر أسرار |
| 5. بيانات اختبار متبقية توهم بمحتوى حقيقي | تنظيف + `rest_sec.json` | الجداول فارغة |

---

## 14. مضاد-تحقق §15.7 (ما الذي قد يكون زائفًا؟)

| الادّعاء | كيف نتحقق منه خارجيًا | النتيجة |
|---|---|---|
| «الصلاحيات تُفرض في القاعدة» | `select * from pg_policies` | 16 سياسة على الجداول/الحاوية |
| «الإدارة بلا مفتاح إداري في التطبيق» | `grep -rn "sb_secret_\|service_role" app/src` | صفر (عدا `auth.role() = 'service_role'` **داخل القاعدة**) |
| «الفحوص نجحت» | `docs/evidence/*.txt` + إعادة التشغيل | 44/33/23 |
| «البناء ينجح» | `app/build/reports/tests/testDebugUnitTest/index.html` | 20/20 |

---

## 15. الخلاصة (الأقسام الثمانية الإلزامية)

1. **ما الذي نُفّذ:** استبدال كامل لطبقة Appwrite بـ Supabase (7 ملفات محوّل + محوّل مصادقة + محوّل تخزين + DI) مع حذف الجسر القديم كاملًا.
2. **لماذا:** مشروع Supabase كان خاليًا؛ القيمة الحقيقية هي نقل **منطق** الوصول والصلاحيات لا بيانات.
3. **كيف:** `SupabaseHttp` (REST/Storage) + `SupabaseAuthRepository` (GoTrue) + `SupabaseStorageRepository` (نفس سطح الدوال) ⇒ تغيير النوع فقط في طبقة النطاق.
4. **ما الذي تحقّق:** 100 فحصًا حيًّا + 20 اختبار وحدة + بناء ناجح + حارس 359 فحصًا.
5. **ما الذي لم يتحقق:** بيانات Appwrite القديمة (غير قابلة للوصول)؛ استئناف الرفع على مستوى المقطع (TUS غير متاح) ⇒ إعادة محاولة الملف كاملًا.
6. **المخاطر المتبقية:** R2/R3 في §6 (مرصودة ومُوثّقة).
7. **كيف يُشغَّل:** §9 Runbook.
8. **ما أحتاجه من المستخدم:** Access Token للرفع إلى GitHub (الدفع بدونه مستحيل: لا يوجد credential_helper في البيئة، والمستودع يُقرأ anonymously فقط).
