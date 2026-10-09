# إعداد GitHub Actions للبناء السحابي

تم رفع ملف workflow في هذا المستودع باسم `github-actions-workflow.yml`.

## الخطوات المطلوبة لإعداد البناء التلقائي:

### الطريقة 1: نقل الملف يدوياً

1. قم بإنشاء مجلد `.github/workflows` في جذر المستودع
2. انسخ محتوى ملف `github-actions-workflow.yml` إلى `.github/workflows/build.yml`
3. قم برفع التغييرات إلى المستودع

### الطريقة 2: استخدام GitHub UI

1. اذهب إلى تبويب "Actions" في المستودع
2. اختر "New workflow"
3. اختر "set up a workflow yourself"
4. الصق محتوى ملف `github-actions-workflow.yml`
5. قم بحفظ الملف

### ملاحظات مهمة:

- الملف يستخدم JDK 17 و Android SDK platform-35
- يحتاج المستودع إلى secret باسم `BULED_REPO_TOKEN` لرفع APK إلى مستودع buled
- يمكن تشغيل الـ workflow يدوياً من تبويب Actions بعد إعداده

### التحقق من البناء:

بعد إعداد الـ workflow، سيتم:
1. بناء المشروع تلقائياً عند كل push إلى main
2. إنتاج APK في `app/build/outputs/apk/releaseFull/app-releaseFull.apk`
3. رفع APK كـ artifact لمدة 30 يوم
4. محاولة رفع APK إلى مستودع buled (إذا تم إعداد BULED_REPO_TOKEN)
