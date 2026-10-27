package com.unihub.app.notifications

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.unihub.app.MainActivity
import com.unihub.app.R
import com.unihub.app.data.auth.CloudUserAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * إشعار محلي حقيقي (NotificationManager على جهاز المشرف نفسه) عند انضمام مستخدم
 * جديد بتسجيل ذاتي. هذا ليس إشعار push عن بُعد (يتطلّب بنية Firebase/FCM غير
 * موجودة في هذا المشروع ولا بيانات اعتماد لها) — الإشعار يظهر عندما يكون تطبيق
 * المشرف قد فحص سجلّ المستخدمين فعليًا (عند فتح التطبيق، أثناء بقائه بالمقدّمة
 * بفضل نبضة [com.unihub.app.data.cloud.CloudSyncManager]، أو عند الضغط يدويًا
 * على "تحديث من السحابة" في شاشة إدارة المستخدمين). هذا الحدّ موثَّق صراحةً في
 * مراجعة.md حتى لا يُفهَم كإشعار فوري أثناء إغلاق التطبيق تمامًا.
 */
@Singleton
class NewUserNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun notifyNewUsers(users: List<CloudUserAccount>): Boolean {
        if (users.isEmpty()) return false
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (manager.getNotificationChannel(NotificationChannels.NEW_USER_REGISTRATIONS)?.importance == NotificationManager.IMPORTANCE_NONE) return false

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val first = users.first()
        val title: String
        val shortText: String
        val lines: List<String>
        if (users.size == 1) {
            title = "مستخدم جديد انضمّ إلى التطبيق"
            shortText = "${first.username} — يحتاج مراجعة صلاحياته"
            lines = listOf("البريد: ${first.username}", "راجع صلاحياته من الإعدادات ← إدارة المستخدمين")
        } else {
            title = "${users.size} مستخدمين جدد انضمّوا"
            shortText = "${first.username} و ${users.size - 1} آخرين"
            lines = users.take(6).map { "• ${it.username}" } + if (users.size > 6) {
                listOf("… و ${users.size - 6} آخرين")
            } else {
                listOf("راجع صلاحياتهم من الإعدادات ← إدارة المستخدمين")
            }
        }

        val style = NotificationCompat.InboxStyle().also { inbox ->
            inbox.setBigContentTitle(title)
            lines.forEach(inbox::addLine)
        }

        val notification = NotificationCompat.Builder(context, NotificationChannels.NEW_USER_REGISTRATIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(shortText)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()

        return runCatching {
            manager.notify(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val NOTIFICATION_ID = 9044
    }
}
