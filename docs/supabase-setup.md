# إعداد Supabase لمشروع UniHub

هذا المستند يشرح **كيف أُعيد بناء تكامل UniHub السحابي على Supabase**:
ما الذي يجب أن يوجد في المشروع، وكيف يُطبَّق، وكيف يُفحص.

> **ملاحظة عن المفاتيح:** التطبيق يحتوي على **مفتاح النشر** (`sb_publishable_…`) فقط،
> وهو مفتاح عام بحكم التصميم. لا يوجد في التطبيق أي مفتاح سري (`sb_secret_…`)،
> ولا في ملفات المشروع ولا في `مراجعة.md` ولا في الحزمة النهائية.
> المفاتيح السرّية تُستعمل في بيئة الإدارة فقط: ترحيلات القاعدة وسكربتات الفحص.

---

## 1) ما الذي يتغيّر مقابل Appwrite

| الوظيفة | Appwrite (قديم) | Supabase (الآن) |
|---|---|---|
| حساب المستخدم | مشروع Auth منفصل + جسر Node | `auth.users` + `public.profiles` |
| صلاحيات المستخدم | Labels على الحساب (`admin`, `unihubdownload`…) | أعمدة `is_admin/can_download/can_upload/can_modify` |
| إدارة المستخدمين (إنشاء/حذف/كلمة مرور) | جسر Node بصلاحية `users.write` | دوال `SECURITY DEFINER` داخل القاعدة |
| ملفات المستخدم | حاوية في مشروع Storage منفصل | جدول `public.cloud_objects` + حاوية `unihub-files` |
| فهرس المكتبة | كائن `unihub_manifest.json` بإصدارات | جدول `public.cloud_manifests` (المفتاح = معرّف الجيل) |
| الصلاحيات على الملفات | صلاحيات الحاوية + هوية «ظل» | **RLS** على `storage.objects` و`public.cloud_*` |
| رمز التخزين القصير (300 ثانية) | جسر Appwrite | **لا شيء**: رمز المستخدم نفسه هو التفويض |

النتيجة العملية: حُذفت طبقة الجسر بالكامل (`service/appwrite`)، وحُذفت مكتبة
Appwrite من التبعيات، وبقيت طبقة `data/cloud` (1366 سطرًا) **بلا سطر واحد مُعدَّل**.

---

## 2) المخطط

الملف: [`supabase/migrations/20261023000100_unihub_cloud_schema.sql`](../supabase/migrations/20261023000100_unihub_cloud_schema.sql)
الملف: [`supabase/migrations/20261023000200_admin_user_lifecycle.sql`](../supabase/migrations/20261023000200_admin_user_lifecycle.sql)
الملف: [`supabase/migrations/20261025000100_bootstrap_first_admin.sql`](../supabase/migrations/20261025000100_bootstrap_first_admin.sql)
الملف: [`supabase/migrations/20261025000200_restrict_first_admin_bootstrap.sql`](../supabase/migrations/20261025000200_restrict_first_admin_bootstrap.sql)

### 2.1 `public.profiles` — ملف الحساب

| العمود | المعنى |
|---|---|
| `id` | مفتاح أساسي = `auth.users.id` (CASCADE) |
| `email` | بريد الحساب (مطبّع) |
| `display_name` | الاسم الظاهر |
| `is_admin` | مشرف: كل الصلاحيات |
| `is_active` | حساب موقوف ⇒ لا وصول سحابي إطلاقًا |
| `can_download` | قراءة الملفات |
| `can_upload` | رفع الملفات |
| `can_modify` | تعديل/حذف الملفات |

**تسلسل الصلاحيات** (نفس تسلسل Appwrite): تعديل⇒رفع⇒قراءة.
مطبَّق في مكانين متّفقَين:
- `admin_set_permissions` يرفض أي تركيبة غير متسلسلة (`invalid_permissions`).
- `effective_can_download/upload/modify` تحسب الصلاحية الفعلية.

### 2.2 `public.cloud_objects` — بيانات الملفات البعيدة

- `id` = `f` + أول 35 محرفًا من `sha256(object_key)` — **نفس** صيغة Appwrite.
- `object_key` = المفتاح المنطقي (قد يحوي العربية) — فريد.
- `storage_path` = مسار الحاوية = `id` (ASCII آمن — انظر §5).
- `owner_id` → `auth.users` بـ `ON DELETE SET NULL`.

### 2.3 `public.cloud_manifests` — فهرس المكتبة

- `id` = `m<generation:016d>_<sha17>` — **نفس** صيغة Appwrite.
- قيد التفرّد على `id` هو ما يحوّل الكتابة المتزامنة إلى **409** (تعارض) بدل فقدان بيانات.
- `payload` من نوع `jsonb` (المفاتيح مرتَّبة ⇒ لا تعارض من اختلاف ترتيب JSON).

### 2.4 سياسات RLS

| الجدول | SELECT | INSERT | UPDATE | DELETE |
|---|---|---|---|---|
| `profiles` | صفّك فقط | — | — | — |
| `cloud_objects` | `can_download` | `can_upload` | `can_modify` | `can_modify` |
| `cloud_manifests` | `can_download` | `can_upload and can_modify` | `can_modify` | `can_modify` |
| `storage.objects` | `can_download` | `can_upload` | `can_modify` | `can_modify` |

> **القاعدة:** RLS هي التنفيذ، والواجهة مجرد عرض. من حقّ المستخدم تعديل طلبه
> بالقوة، فيرفضه الخادم — وهذا مُختبَر حيًّا (فحص RLS).

---

## 3) دوال الإدارة (داخل القاعدة)

| الدالة | الأثر |
|---|---|
| `admin_list_users()` | قائمة المستخدمين (للمشرف) |
| `admin_set_permissions(target, download, upload, modify)` | تغيير الصلاحيات |
| `admin_set_active(target, active)` | إيقاف/تفعيل حساب |
| `admin_revoke_sessions(target)` | إبطال جلسات المستخدم |
| `admin_create_user(email, password, download, upload, modify)` | إنشاء حساب بكلمة مرور |
| `admin_delete_user(target)` | حذف حساب (وجلساته) |
| `admin_set_password(target, new_password)` | تعيين كلمة مرور + إبطال الجلسات |

كلها `SECURITY DEFINER` مع `set search_path = public`، وتقبل `service_role`
(لبيئة الإدارة) أو **رمز مشرف** (للتطبيق). لا مفتاح إداري في التطبيق.

### 3.1 ثلاثة أخطاء حقيقية اكتُشفت حيًّا في `auth.users`

حُفظت كلها في نصّ الترحيلة، وهي مفيدة لمن سيعدّل الدوال لاحقًا:

1. **بلا صف هوية (`auth.identities`)** ⇒ رفض تسجيل الدخول بكلمة المرور.
2. **`auth.identities.email` عمود مولَّد** (`lower(identity_data->>'email')`)
   ⇒ لا يجوز إدراجه صراحةً (خطأ `428C9`).
3. **أعمدة الرموز النصّية يجب أن تكون `''` لا `NULL`**
   (`confirmation_token`, `recovery_token`, `email_change`,
   `email_change_token_new/current`, `phone_change`, `phone_change_token`,
   `reauthentication_token`) ⇒ NULL يكسر استعلامات GoTrue بـ
   «converting NULL to string is unsupported» (500).

---

## 4) التطبيق: أين ذهب كل شيء

| الملف | الدور |
|---|---|
| `data/supabase/SupabaseConfig.kt` | الإحداثيات العامة ومفتاح النشر فقط |
| `data/supabase/SupabaseHttp.kt` | عميل REST خام (بلا مكتبة جديدة) |
| `data/supabase/SupabaseAuthRepository.kt` | المصادقة والجلسات وإدارة المستخدمين |
| `data/supabase/SupabaseStorageTransport.kt` | نقل الملفات (نفس سطح الدوال القديم) |
| `data/supabase/SupabaseStorageAuthorization.kt` | التفويض (رمز المستخدم) |
| `data/supabase/SupabaseAdminErrors.kt` | ترجمة أخطاء القاعدة إلى نص عربي للمستخدم |
| `di/SupabaseModule.kt` | providing `SupabaseHttp` عبر Hilt |
| `data/cloud/CloudFileProtocol.kt` | بروتوكول الملفات (نُقل من Appwrite بلا تغيير دلالة) |
| `data/cloud/StorageAuthorizer.kt` | تفويض الملفات (نُقل من Appwrite) |

**بلا `supabase-kt`**: المشروع كان يملك نمط REST خامًا بـ `HttpURLConnection`
(طبيعة تطبيق.md §14: لا تضف Dependency بلا حاجة).

---

## 5) مفاتيح الحاوية: لماذا لا نستخدم المفتاح المنطقي؟

Supabase Storage يرفض المفاتيح غير اللاتينية (مُتحقَّق حيًّا):

```
POST /storage/v1/object/unihub-files/مادة/ملف.pdf  ⇒  400 {"error":"InvalidKey"}
```

ومفاتيح UniHub قد تحوي العربية والمسافات وأسماء مجلدات المستخدم. لذلك:

- **المفتاح المنطقي** (= مسار الملف عند المستخدم) يبقى في
  `cloud_objects.object_key` و`name` — وهو ما تعرضه الواجهة وتقارنه.
- **مسار الحاوية** = `id` = `f` + 35 محرفًا ست عشريًا: لاتيني صغير دائمًا،
  فريد، وقابل لإعادة الاشتقاق بلا عشوائية.

بذلك لا يضيع أي رابط، وتبقى المكتبة المحلية صالحة، والرفع لا يفشل بسبب الاسم.

---

## 6) كيف تُطبَّق المخططات

مع موصل Supabase (المستعمل في هذه الجلسة):

```
supabase__create_migration(name="unihub_cloud_schema",      query=<محتوى 20261023000100>)
supabase__create_migration(name="admin_user_lifecycle",       query=<محتوى 20261023000200>)
supabase__create_migration(name="bootstrap_first_admin",      query=<محتوى 20261025000100>)
supabase__create_migration(name="restrict_first_admin_bootstrap", query=<محتوى 20261025000200>)
```

أو بأي وسيلة أخرى (psql / Supabase CLI / لوحة Supabase ← SQL Editor):
نفّذ الملفات الأربعة بالترتيب الزمني. الترحيل الأخير يسحب صلاحيات العميل العام
حتى إذا كانت النسخة السابقة من دالة التمهيد قد نُشرت.

> **فحص الجدول:** بعد التطبيق يجب أن تكون القيم:
> `tables=3, public_policies=12, storage_policies=4, bucket=1`.

---

## 7) إنشاء حساب المشرف الأول بأمان

لا يُنشئ تطبيق Android حساب مشرف ولا يتيح تسجيلًا عامًا. مفتاح النشر معروف
للعملاء، لذلك لا يكفي وحده لإثبات أن من يطلب إنشاء المشرف هو مالك المشروع.

1. طبّق ترحيلات المخطط الأربع أعلاه من لوحة Supabase الموثوقة.
2. من **Authentication → Users** أنشئ/ادعُ الحساب المقصود، واستعمل كلمة مرور
   جديدة وفريدة؛ أكّد البريد وفق إعدادات المشروع.
3. في **SQL Editor** امنح الحساب المقصود صلاحية المشرف (استبدل `<ADMIN_EMAIL>`
   بالبريد داخل اللوحة فقط، ولا تحفظ كلمة المرور في SQL أو المستودع):

```sql
update public.profiles as p
set is_admin = true,
    is_active = true,
    can_download = true,
    can_upload = true,
    can_modify = true,
    updated_at = now()
from auth.users as u
where p.id = u.id
  and lower(u.email) = lower('<ADMIN_EMAIL>')
returning p.id, p.email;
```

يجب أن يعيد التحديث صفًا واحدًا؛ إذا أعاد صفرًا فتوقف وتحقق من الحساب وtrigger
`on_auth_user_created` بدل تعطيل حارس الصلاحيات. ثم تحقّق أن المشرف النشط موجود:

```sql
select count(*) as active_admins
from public.profiles
where is_admin and is_active;

select
  has_function_privilege('anon', 'public.bootstrap_first_admin(text,text)', 'EXECUTE') as anon_can_bootstrap,
  has_function_privilege('authenticated', 'public.bootstrap_first_admin(text,text)', 'EXECUTE') as authenticated_can_bootstrap,
  has_function_privilege('service_role', 'public.bootstrap_first_admin(text,text)', 'EXECUTE') as service_role_can_bootstrap;
```

المتوقع: `anon_can_bootstrap=false` و`authenticated_can_bootstrap=false`.
لا تضع مفتاح `service_role` في Android أو GitHub أو المحادثات. إذا ظهر
`PGRST202` فترحيلة RPC غير منشورة؛ إذا ظهر `invalid login credentials` فراجع
وجود المستخدم وكلمة مروره في **Authentication → Users**، ولا تحاول إنشاء مشرف
عام من التطبيق.

---

## 8) أدلة الاختبار وطرق إعادة التشغيل

> أرقام اختبارات Supabase الحية أدناه نتائج محفوظة سابقًا في هذا المستودع؛
> لم تُعَد على مشروع الإنتاج في مهمة الإصلاح هذه. الاختبارات المحلية لا تحتاج
> مفتاح خدمة؛ أما scripts السحابية فتتطلب بيئة اختبار مخصصة ومفاتيح سرية.

| السكربت | ما يثبته | النتيجة |
|---|---|---|
| `supabase/tests/rls_smoke_test.sh` | 29 فحصًا: رفض anon، عزل بين الحسابات، منع تصعيد الصلاحيات، صلاحيات الحاوية، دوال الإدارة | **29/29** |
| `supabase/tests/admin_lifecycle_test.sh` | 21 فحصًا: إنشاء/تعديل/حذف مستخدم، كلمة المرور، إبطال الجلسات، رفض غير المشرف | **21/21** |
| `supabase/tests/cloud_files_test.sh` | 14 فحصًا: رفع، صفّ، قائمة، بيان (409 عند التعارض)، تنزيل، Range 206، حذف | **14/14** |
| `./gradlew :app:testDebugUnitTest` | 23 اختبار JVM: البروتوكول، الصلاحيات، ترويسات HTTP ورسائل أخطاء RPC | **23/23 في آخر تشغيل محلي** |

**التشغيل:**

```bash
export SUPABASE_URL="https://<ref>.supabase.co"
export SUPABASE_PUBLISHABLE_KEY="sb_publishable_..."   # عام
export SUPABASE_SECRET_KEY="sb_secret_..."             # الإدارة فقط
bash supabase/tests/rls_smoke_test.sh
bash supabase/tests/admin_lifecycle_test.sh
bash supabase/tests/cloud_files_test.sh
```

المخرجات المحفوظة في `docs/evidence/`.

---

## 8) حدود معروفة (موثّقة صراحةً)

1. **الرفع المتجزّأ (TUS) غير متاح** في هذا المشروع:
   `POST /storage/v1/upload/resumable/...` ⇒ 400 «Metadata header is required».
   ⇒ الرفع بطلب واحد مع `x-upsert` وإعادة محاولة للملف كاملًا.
   **الاستئناف أثناء التنزيل ما زال يعمل** (`Range` ⇒ 206، مُتحقَّق حيًّا).
2. **بيانات Appwrite القديمة غير منقولة**: مشروعا Appwrite القديمان
   (`6acd036f0022a495a9df` حسابات، `6acd0a6800189ccc164a`/`6acd11520015520ba016`
   تخزين) غير قابلين للوصول بالمفاتيح المتاحة في هذه الجلسة.
   ⇒ **لا يُقال «نُقلت البيانات»**؛ المشروع Supabase بدأ فارغًا (0 مستخدم،
   0 جدول، 0 حاوية) وهذا موثّق في `docs/evidence/capability-probe.txt`.
3. **رفع الترقية/المزامنة كان مجزّأ 5 MiB** في Appwrite؛ صار طلبًا واحدًا.
   الملفات الكبيرة تُرفع كاملًا؛ فشل الرفع = إعادة محاولة كاملة (آمنة
   لأن المفتاح مشتقّ من المحتوى).
4. **البيانات القديمة على الجهاز تبقى**: روابط المكتبة المحلية مشتقّة من
   `CloudFileProtocol` الذي لم تتغيّر صيغته، فلا تفقد ملفاتك المحلية.
