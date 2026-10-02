package com.unihub.app.notifications

import android.Manifest
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.unihub.app.MainActivity
import com.unihub.app.R
import com.unihub.app.core.common.Formatters
import com.unihub.app.data.cloud.RemoteCloudFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * مسؤول إظهار إشعار فوري للمستخدم عند اكتشاف ملفات جديدة على خادم Cloudflare R2
 * غير موجودة في تخزين الهاتف المحلي، مع عرض اسم الملف الجديد وحجمه (وأسماء وأحجام
 * بقية الملفات إن وجدت أكثر من ملف).
 *
 * جولة تعليمات.md: النقرة تفتح التطبيق فقط؛ أزيل التوجيه المباشر إلى شاشة السحابة
 * لأن فتحها محصور في زرّي الشريط العلوي (الرئيسية والملفات) توحيداً لنقاط الدخول.
 */
@Singleton
class CloudFileNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun notifyNewRemoteFiles(files: List<RemoteCloudFile>): Boolean {
        if (files.isEmpty()) return false
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        // فحص صريح لإذن POST_NOTIFICATIONS على أندرويد 13+: الفحص أعلاه يغطي
        // تعطيل المستخدم للإشعارات من الإعدادات، وهذا يغطي رفض الإذن وقت التشغيل
        // فيمنع محاولة عرض مرفوضة أصلاً (سبب تحذير MissingPermission السابق).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (manager.getNotificationChannel(NotificationChannels.CLOUD_FILES)?.importance == NotificationManager.IMPORTANCE_NONE) return false

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val first = files.first()
        val firstSize = Formatters.fileSize(first.size)

        val title: String
        val shortText: String
        val expandedLines: List<String>

        if (files.size == 1) {
            title = "ملف جديد متاح على الخادم"
            shortText = "${first.fullDisplayName} (${firstSize})"
            expandedLines = listOf(
                "الاسم: ${first.fullDisplayName}",
                "الحجم: $firstSize",
                "متاح للتنزيل من شاشة السحابة"
            )
        } else {
            val totalBytes = files.sumOf { it.size }
            val totalFormatted = Formatters.fileSize(totalBytes)
            val othersCount = files.size - 1
            title = "توجد ${Formatters.fileCountLabel(files.size)} جديدة على الخادم ($totalFormatted)"
            shortText = "${first.fullDisplayName} ($firstSize) و $othersCount أخرى"
            expandedLines = files.take(6).map { file ->
                "• ${file.fullDisplayName} — ${Formatters.fileSize(file.size)}"
            } + if (files.size > 6) {
                listOf("… و ${files.size - 6} ملفات أخرى (الإجمالي: $totalFormatted)")
            } else {
                listOf("متاحة للتنزيل من شاشة السحابة")
            }
        }

        val inboxStyle = NotificationCompat.InboxStyle().also { style ->
            style.setBigContentTitle(title)
            expandedLines.forEach { line -> style.addLine(line) }
        }

        val notification = NotificationCompat.Builder(context, NotificationChannels.CLOUD_FILES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(shortText)
            .setStyle(inboxStyle)
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

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    companion object {
        const val NOTIFICATION_ID = 9042
    }
}
