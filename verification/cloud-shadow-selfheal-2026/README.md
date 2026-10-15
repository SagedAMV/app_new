# إصلاح القفل الدائم «تعارض» عند الاتصال بالتخزين السحابي — 2026-10-16

## المشكلة المرصودة

لقطة شاشة المستخدم (`1792091539603.png` في جذر المستودع): شاشة «الملفات السحابية» تعرض
«الحساب موجود مسبقًا أو حدث تعارض؛ حدّث القائمة وأعد المحاولة» مع فشل رفع في الخلفية
برسالة «جارٍ تنفيذ عملية مصادقة أخرى»، رغم نجاح تسجيل الدخول في مشروع المستخدمين.

## السبب الجذري

`shadowOrNull()` في جسر Appwrite (نسختا Kotlin وNode) كان يرمي `409 conflict` لكل هوية
تخزين تقنية موجودة ناقصة الوسوم، دون أي مسار إصلاح. هوية نصف مُهيأة (فُقدت كتابة الوسوم
بعد الإنشاء بسبب مهلة/انقطاع) أو هوية بمخطط وسوم قديم تعني إذن قفلًا دائمًا: كل `storageToken`
يفشل بنفس الرمز، فلا رفع ولا تنزيل ولا فهرسة، و«حدّث القائمة» لا يغيّر شيئًا.

## الإصلاح

- `service/appwrite/auth-bridge/BridgeService.kt` و`service/appwrite/node-bridge/src/main.js`:
  تبنٍّ ذاتي الشفاء للهوية التقنية اليتيمة (بلا بريد/هاتف وبلا وسم `src…` غريب) مع إعادة
  وسمها في `syncShadow()`؛ الرفض الصارم محفوظ للهوية القابلة لتسجيل الدخول ولهوية مرتبطة
  بمصدر آخر.
- `app/src/main/java/com/unihub/app/data/auth/CloudAuthManager.kt`: انتظار محدود (15 ثانية)
  لقفل المصادقة بدل الفشل الفوري بـ`tryLock`، وهو مصدر رسالة «جارٍ تنفيذ عملية مصادقة أخرى»
  عند تزامن الفحص الخلفي مع رفع يبدأه المستخدم.
- `app/src/main/java/com/unihub/app/data/appwrite/AppwriteAuthBridge.kt`: رسالة التعارض
  أصبحت توجّه المستخدم وإعادة المحاولة ثم المشرف بدل «حدّث القائمة» المستحيلة.
- `service/appwrite/src/test/kotlin/io/openruntimes/kotlin/BridgeServiceTest.kt`: ثلاثة
  اختبارات جديدة (تبنّي اليتيمة، إصلاح المخطط القديم، رفض ربط مصدر آخر) تُشغَّل في CI
  عبر `./gradlew :appwrite-bridge:test`.

## أمر التشغيل والمخرجات الفعلية

```bash
node verification/cloud-shadow-selfheal-2026/bridge_orphan_harness.mjs
```

المخرجات على الشيفرة المصححة (2026-10-16):

```
PASS  S1 orphan adopted -> 200
PASS  S1 orphan re-labelled
PASS  S1 orphan enabled + jwt minted
PASS  S2 sign-in capable refused -> 409 conflict
PASS  S2 disabled before refusal
PASS  S3 foreign source refused -> 409 conflict
PASS  S3 no writes to storage identity
PASS  S4 fresh provisioning -> 200
PASS  S4 labels complete
ALL 9 CHECKS PASSED
```

إثبات Red→Green على النسخة السابقة (`git show HEAD:service/appwrite/node-bridge/src/main.js`):

```
FAIL  S1 orphan adopted -> 200  -> status=409 body={"code":"conflict"}
FAIL  S1 orphan re-labelled  -> labels=[]
FAIL  S1 orphan enabled + jwt minted -> status=true dur=0
PASS  S2/S3/S4 ...
3/9 CHECKS FAILED
```

أي أن العطل القديم يُنتج حرفيًا رمز `conflict` الذي ظهر للمستخدم، والإصلاح يزيله مع بقاء
كل الرفضات الأمنية كما هي.

## حدود هذا التحقق

- لا يوجد JDK في بيئة الفحص، لذا اختبارات Kotlin الجديدة لم تُشغَّل محليًا؛ يشغّلها CI
  (`.github/workflows/build.yml`). الـharness أعلاه يغطي السلوك نفسه على المنفذ المنشور فعليًا.
- الحالة الحية لهوية التخزين في مشروع الملفات لا تُفحص بدون مفاتيح الخادم؛ إن استمر التعارض
  بعد إعادة نشر الخدمة فاتبع `docs/appwrite-setup.md` §5.
