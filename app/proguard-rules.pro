# =============================================================================
# قواعد ProGuard/R8 — تطبيق UniHub
# =============================================================================
# الحالة: التصغير (isMinifyEnabled + isShrinkResources) مُفعَّل عمداً في Release.
# هذا الملف هو السبب الذي يجعل النسخة المصغّرة تعمل الآن — كل قسم أدناه يحمي
# مسار انعكاس (reflection) حقيقياً في التطبيق أو مكتباته، ومعه سبب وجود القاعدة.
#
# السبب الجذري لكراش الإقلاع التاريخي (شُخِّص وأُصلح في جلسة 2026 — راجع
# تقرير_جلسة_تفعيل_النسخة_المصغرة.md في جذر المشروع):
#   كان R8 في وضع Full Mode (الافتراضي في AGP 8.7) يُسقط «المُنشئ بلا وسائط»
#   للأصناف المولَّدة من Room (*_Impl) — وتحديداً قاعدة WorkManager الداخلية
#   androidx.work.impl.WorkDatabase_Impl التي لا تظهر في كود التطبيق إطلاقاً —
#   بينما يُبقي الصنف نفسه داخل الـ dex. تحميل هذه التطبيقات يتم انعكاسياً عبر
#   Class.forName("<اسم-القاعدة>_Impl").newInstance() داخل
#   RoomDatabase.getGeneratedImplementation، فلا يراه R8 إطلاقاً.
#   لذلك كان فحص «هل الصنف موجود في الـ dex؟» ينجح بينما ينهار الجهاز الحقيقي
#   بـ NoSuchMethodException عند أول إقلاع. القاعدة (1) أدناه هي الإصلاح الجذري.
# =============================================================================

# --- 0) مقروئية التتبعات بعد التمويه ----------------------------------------
# الإبقاء على اسم المصدر وأرقام الأسطر كي تبقى سجلات الكراش المستقبلية قابلة
# للقراءة مع mapping.txt (الذي ينتجه البناء المصغّر) بدل أرقام مجهولة.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# --- 1) Room: الإصلاح الحاسم (السبب الجذري للكراش) ---------------------------
# Room يحمّل التطبيق المولَّد لأي RoomDatabase انعكاسياً وقت التشغيل:
#   Class.forName("<اسم-القاعدة>_Impl").newInstance()
# فيجب أن يبقى سالماً: (أ) اسم صنف القاعدة نفسه (لأن اسم _Impl يُشتق منه)،
# (ب) اسم التطبيق المولَّد، (ج) مُنشئه بلا وسائط — وهذا الأخير بالضبط ما كان
# R8 full mode يُسقطه.
#
# القاعدة الواحدة التالية تغطي: قاعدة التطبيق (UniHubDatabase_Impl) وأي قاعدة
# داخلية تجلبها المكتبات — وأهمها WorkDatabase_Impl الخاصة بـ WorkManager،
# سبب كراش الإقلاع الفعلي. (هذا هو الإصلاح نفسه الذي أضافته Google رسمياً في
# Room 2.7.0-beta01 تحت b/392657750 — والتطبيق هنا على Room 2.6.1 بدونه،
# لذا نطبقه يدوياً.)
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }

# شبكة أمان صريحة لطبقة Room الخاصة بالتطبيق (الكيانات وDAO والمحوّلات مرجعية
# من كود Room المولَّد فيبقيها R8 تلقائياً — نُبقيها صراحةً كي لا تكون ضحية
# أي تفاؤل مستقبلي في التحليل):
-keep class com.unihub.app.data.local.UniHubDatabase { *; }
-keep interface com.unihub.app.data.local.dao.** { *; }
-keep class com.unihub.app.data.local.entity.** { *; }
-keep class com.unihub.app.data.local.Converters { *; }

# --- 2) Kotlin enums ----------------------------------------------------------
# نضمن بقاء values()/valueOf() سليمة: تُستخدم في محوّلات Room (عبر
# entries.firstOrNull { it.name == value }) وفي تحليل النسخ الاحتياطية.
# (القاعدة الافتراضية proguard-android-optimize تغطي هذا أصلاً — نثبّتها
# صراحةً كي لا تختفي إن تبدّلت القواعد الافتراضية في إصدارات AGP القادمة.)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- 3) WorkManager ------------------------------------------------------------
# 3أ) العاملات: يُنشئها WorkManager انعكاسياً عبر اسم الصنف المخزَّن في قاعدته
# الداخلية؛ يجب إبقاء الصنف ومُنشئه العلني (Context, WorkerParameters) سليماً.
# (قاعدة work-runtime المدمجة تغطي هذا — نثبّتها ونخصّ عامل التطبيق بالذكر.)
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.unihub.app.notifications.ReminderWorker { *; }

# 3ب) إقلاع WorkManager نفسه: يتم عند أول تشغيل عبر androidx.startup — يقرأ
# InitializationProvider اسم androidx.work.impl.WorkManagerInitializer من
# metadata في الـ manifest المدمج ويُنشئه بـ Class.forName. أي سقوط لهذه
# السلسلة يعني StartupException عند إقلاع النسخة المصغّرة.
-keep class * extends androidx.startup.Initializer { *; }

# --- 4) kotlinx.serialization ---------------------------------------------------
# مساران يعتمدان عليه وقت التشغيل عبر مرافِقات (Companions) مولَّدة:
#   - التنقل الآمن الأنواع Navigation 2.8 (composable<Route>/toRoute — تحلّ
#     المِسيرات عبر serializer() انعكاسياً من Companion)
#   - أي تسلسل مستقبلي لبيانات التطبيق
# القواعد التالية هي الرسمية من توثيق kotlinx.serialization لإعدادات R8:
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

# --- 5) Hilt ---------------------------------------------------------------------
# المكوّنات المولَّدة يجب أن تبقى بأسمائها كما هي (قواعد Hilt المدمجة تغطي
# الجزء الأكبر — هذه طبقة أمان إضافية من توثيق Dagger الرسمي):
-keep class dagger.hilt.internal.aggregatedroot.codegen.** { *; }
-keep class hilt_aggregated_deps.** { *; }
-keep,allowobfuscation,allowshrinking class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper

# =============================================================================
# ملاحظات صيانة (لا تُحذف):
# - لا تضف -dontwarn شاملة: إخفاء التحذيرات جملةً يخفي مشاكل حقيقية. إن ظهر
#   تحذير Missing class في بناء مستقبلي عالج سببه أو أضف dontwarn موجّهاً ومعللاً.
# - لا تُضف قواعد «keep كل شيء» (مثل -dontobfuscate أو -keep class ** {*;}) —
#   تلغي فائدة التصغير كاملة وتُعيد حجم النسخة لما قبله.
# - أي مكتبة جديدة تعتمد الانعكاس (Gson/Moshi/Reflections...) تتطلب قسماً
#   خاصاً بها هنا قبل تفعيل بنائها المصغّر.
# =============================================================================
