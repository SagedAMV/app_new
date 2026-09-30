package com.unihub.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** قنوات الإشعارات — تُنشأ مرة واحدة عند بدء التطبيق */
object NotificationChannels {

    const val EXAMS = "exams"
    const val TASKS = "tasks"
    const val CLOUD_FILES = "cloud_files"

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
    }
}
