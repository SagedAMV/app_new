package com.unihub.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** قنوات الإشعارات — تُنشأ مرة واحدة عند بدء التطبيق */
object NotificationChannels {

    const val EXAMS = "exams"
    const val TASKS = "tasks"
    const val CLOUD_FILES = "cloud_files"
    const val CLOUD_TRANSFERS = "cloud_transfers"
    const val NEW_USER_REGISTRATIONS = "new_user_registrations"

    fun create(context: Context) {
        // minSdk = 26 (أندرويد 8.0) — قنوات الإشعارات متاحة دائماً، فلا حاجة
        // لفحص إصدار النظام (فحص قديم أُزيل بتنبيه ObsoleteSdkInt من lint).
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(EXAMS, "تذكير بالامتحانات", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "إشعار الامتحانات"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(TASKS, "تذكير بالمهام", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "إشعار المهام"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CLOUD_FILES, "ملفات سحابية جديدة", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "ملفات سحابية"
            }
        )

        // قناة النقل: IMPORTANCE_LOW لأن الإشعار مستمر/Ticker ولا يجوز أن يرنّ كل تقدّم
        manager.createNotificationChannel(
            NotificationChannel(CLOUD_TRANSFERS, "نقل الملفات", NotificationManager.IMPORTANCE_LOW).apply {
                description = "حالة نقل الملفات"
                setShowBadge(false)
            }
        )

        // يظهر فقط لحساب مشرف: مستخدم جديد سجّل حسابه ذاتيًا ويحتاج مراجعة صلاحياته.
        manager.createNotificationChannel(
            NotificationChannel(NEW_USER_REGISTRATIONS, "مستخدمون جدد", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "إشعار المشرف عند تسجيل مستخدم جديد حسابه بنفسه"
            }
        )
    }
}
