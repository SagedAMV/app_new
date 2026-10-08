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

    fun create(context: Context) {
        // minSdk = 26 (أندرويد 8.0) — قنوات الإشعارات متاحة دائماً، فلا حاجة
        // لفحص إصدار النظام (فحص قديم أُزيل بتنبيه ObsoleteSdkInt من lint).
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(EXAMS, "تذكير بالامتحانات", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "إشعارات قبل الامتحانات القادمة"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(TASKS, "تذكير بالمهام", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "إشعارات مواعيد استحقاق المهام"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CLOUD_FILES, "ملفات سحابية جديدة", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "إشعارات عند توفر ملفات جديدة على خادم Cloudflare R2 مع أسمائها وأحجامها"
            }
        )

        // قناة النقل: IMPORTANCE_LOW لأن الإشعار مستمر/Ticker ولا يجوز أن يرنّ كل تقدّم
        manager.createNotificationChannel(
            NotificationChannel(CLOUD_TRANSFERS, "نقل الملفات في الخلفية", NotificationManager.IMPORTANCE_LOW).apply {
                description = "تقدّم الرفع والتنزيل في الخلفية مع أزرار الإيقاف المؤقت والإلغاء"
                setShowBadge(false)
            }
        )
    }
}
