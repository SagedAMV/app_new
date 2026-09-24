# قواعد ProGuard/R8 الخاصة بالتطبيق
#
# ⚠️ ملاحظة مهمة: isMinifyEnabled معطّل حالياً عمداً في app/build.gradle.kts
# (راجع التعليق هناك + تقرير_جلسة_إصلاح_كراش_الإقلاع.md) بسبب كراش إقلاع مؤكَّد
# على جهاز حقيقي عند تفعيل R8. القواعد أدناه أُبقيت ووُسِّعت كـ"شبكة أمان"
# احتياطية فقط لو أُعيد تفعيل التصغير يوماً — وليست ترخيصاً لإعادة تفعيله
# دون اختبار كامل على جهاز حقيقي فعلياً (وليس تحققاً ثابتاً من الـ dex فقط).

# --- kotlinx.serialization (مسارات التنقل الآمنة Navigation + النسخ الاحتياطي) ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.unihub.app.**$$serializer { *; }
-keepclassmembers class com.unihub.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.unihub.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Room: الكيانات وDAO وقاعدة البيانات نفسها (تُبنى وتُستخدم عبر أسماء/تعليقات
# توضيحية يعتمد عليها مولّد Room وقت الترجمة؛ حمايتها تمنع أخطاء انعكاس صامتة) ---
-keep class com.unihub.app.data.local.UniHubDatabase { *; }
-keep interface com.unihub.app.data.local.dao.** { *; }
-keep class com.unihub.app.data.local.entity.** { *; }
-keep class com.unihub.app.data.local.Converters { *; }

# --- Kotlin enums: نضمن بقاء values()/valueOf() سليمة (تُستخدم في محوّلات Room
# عبر entries.firstOrNull { it.name == value }) ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- WorkManager: يُنشئ عامل التذكيرات انعكاسياً وقت التشغيل عبر اسم الصنف
# المخزَّن في قاعدة بيانات WorkManager الداخلية؛ يجب إبقاء الصنف ومُنشئه العلني
# سليمين تماماً — هذا هو الفخ الأشهر لكراشات WorkManager بعد التصغير ---
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.unihub.app.notifications.ReminderWorker { *; }

# --- Hilt: المكوّنات المولَّدة يجب أن تبقى بأسمائها كما هي ---
-keep class dagger.hilt.internal.aggregatedroot.codegen.** { *; }
-keep class hilt_aggregated_deps.** { *; }
-keep,allowobfuscation,allowshrinking class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
