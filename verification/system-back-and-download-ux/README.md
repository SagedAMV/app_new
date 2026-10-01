# جولة تعليمات.md — زر الرجوع النظامي + أسماء خيارات التنزيل + شجرة الوجهة

## سياق الدمج (مهم)
أثناء هذه الجلسة وُجدت على البعيد جولة موازية (`cc907eb` بنفس العنوان) نفّذت
المشكلات الثلاث بوحداتها الخاصة: `CloudBackPolicy` و`CloudDownloadDefaults`
و`DestinationFolderTree` مع اختباراتها وفاحصها `verification/session-fixes/verify.sh`.
بعد `pull --rebase` وُحّد الخطّان بدل ترك تنفيذين متوازيين:

- اعتُمدت وحدات الجولة الأولى كما هي (مصدر الحقيقة الملتزم).
- حُذفت الوحدات المكررة التي أنشأتها هذه الجلسة (`BackNavigationPolicy`
  و`FolderTreeRows` مع اختباريهما) — إبقاؤها كان سيصنع كوداً ميتاً.
- أُبقيت الإضافات غير الموجودة في الجولة الأولى:
  1) اعتراض زر الرجوع في **CameraCaptureScreen** (الرجوع من المراجعة يعود
     للكاميرا؛ واللقطات الموجودة تمنع المغادرة الصامتة — نفس فئة المشكلة
     التي طلبت تعليمات.md تعميم إصلاحها على كل الواجهات المتضررة).
  2) طبقة **شريط البحث** في FilesScreen: الرجوع يغلق البحث قبل مغادرة الشاشة.
  3) فحص «معالج رجوع واحد فقط» في FilesScreen بعد إزالة الازدواج الذي أحدثه
     الدمج الآلي.

## ما الذي يُفحص وكيف
### 1) الفاحص الثابت لهذه الجولة (بلا gradle)
```bash
bash verification/system-back-and-download-ux/check-changes.sh
```
خروجه 0 = أخضر بالكامل. المخرجات الخام محفوظة في `static-checks.txt`.

### 2) اختبارات الوحدة (Runbook على آلة التطوير)
```bash
./gradlew testReleaseFullUnitTest \
  --tests "com.unihub.app.feature.files.CloudBackPolicyTest" \
  --tests "com.unihub.app.feature.files.DestinationFolderTreeTest" \
  --tests "com.unihub.app.data.cloud.CloudDownloadPlacementTest"
```

## تصريح رسمي (سياسة الأدلة — تفكير.md §1)
> لا يمكن تشغيل اختبارات gradle داخل جلسة التعديل هذه | الثقة: عالية |
> السبب: لا يتوفر JDK 17 ولا Android SDK في بيئة الجلسة (اشتراطات
> build-release-full.sh) | ما يلزم لتشغيلها: آلة التطوير ثم أوامر الـ Runbook.

البديل المنفّذ: كل منطق الجولة وحدات نقية قابلة لاختبار JVM، واختباراتها
موجودة، والفاحص الثابت يربط الشاشات بتلك الوحدات ويثبت إزالة الآثار القديمة.
