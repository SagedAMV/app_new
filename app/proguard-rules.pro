# قواعد ProGuard الخاصة بالتطبيق
# إبقاء نماذج kotlinx.serialization (تُستخدم في مسارات التنقل الآمنة والنسخ الاحتياطي)
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
