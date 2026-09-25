package com.unihub.app.notifications

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.unihub.app.R

/**
 * عامل يعرض إشعاراً مجدولاً. البيانات (العنوان/النص/القناة/المعرف) تصل عبر
 * inputData، فلا حاجة لقراءة قاعدة البيانات وقت التنفيذ.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE) ?: return Result.success()
        val message = inputData.getString(KEY_MESSAGE).orEmpty()
        val channel = inputData.getString(KEY_CHANNEL) ?: NotificationChannels.TASKS
        val notificationId = inputData.getInt(KEY_NOTIFICATION_ID, 1)

        val notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        // فحص صريح لحالة الإشعارات قبل العرض: على أندرويد 13+ يكون الإذن
        // مرفوضاً إن لم يمنحه المستخدم، وفحص areNotificationsEnabled() هو الحارس
        // الذي يعتمده androidx نفسه — يمنع SecurityException من الأساس بدل
        // ابتلاعه، ويحترم أيضاً تعطيل المستخدم للإشعارات من إعدادات النظام.
        val manager = NotificationManagerCompat.from(applicationContext)
        if (manager.areNotificationsEnabled()) {
            runCatching { manager.notify(notificationId, notification) }
        }
        return Result.success()
    }

    companion object {
        const val KEY_TITLE = "title"
        const val KEY_MESSAGE = "message"
        const val KEY_CHANNEL = "channel"
        const val KEY_NOTIFICATION_ID = "notification_id"
    }
}
