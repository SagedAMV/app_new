package com.unihub.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/** قنوات الإشعارات — تُنشأ مرة واحدة عند بدء التطبيق */
object NotificationChannels {

    const val EXAMS = "exams"
    const val TASKS = "tasks"

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
    }
}
