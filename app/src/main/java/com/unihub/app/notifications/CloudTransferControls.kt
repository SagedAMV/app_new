package com.unihub.app.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.unihub.app.MainActivity
import com.unihub.app.R
import com.unihub.app.data.cloud.CloudSyncManager
import com.unihub.app.data.cloud.CloudSyncScheduler
import com.unihub.app.data.cloud.CloudTransferBatch
import com.unihub.app.data.cloud.CloudTransferKind
import com.unihub.app.data.cloud.CloudTransferQueueLogic
import com.unihub.app.data.cloud.CloudTransferQueueSnapshot
import com.unihub.app.data.cloud.CloudTransferQueueStore
import com.unihub.app.data.cloud.CloudTransferStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * إشعار النقل في الخلفية: يبني نسخة «جارٍ» (بها تقدّم وأزرار) ونسخة «موقوف مؤقتًا».
 *
 * كان الإشعار يختفي عند الضغط على «إيقاف مؤقت» لأن العامل ينهي نفسه فيلغي إشعاره، فلا يبقى
 * لأي زر أثر مرئي ولا وسيلة للاستئناف من خارج التطبيق — وهذه النسخة المقفلة تحفظ الزر حيًا.
 * والبناء في مكان واحد لأن الواجهة والإشعار يخدمان الحالة نفسها (طبيعة تطبيق.md §11).
 */
object CloudTransferNotifier {

    private const val TAG = "CloudTransferNotifier"

    const val NOTIFICATION_ID = 9043
    private const val REQUEST_PAUSE = 9044
    private const val REQUEST_CANCEL = 9045
    /** إشعار النتيجة على قناة أخرى برقم آخر: لا يُلغي المستمر ولا يُبطل أزراره */
    private const val RESULT_ID = 9046

    /** لحظة القبول: أول ما يطمن عليه المستخدم أن طلبه دخل الطابور فعلًا، قبل أي خدمة أمامية */
    fun queued(context: Context, snapshot: CloudTransferQueueSnapshot): Notification =
        baseBuilder(context, "أُضيف إلى النقل في الخلفية", snapshot.summary)
            .setProgress(0, 0, true)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

    /**
     * إشعار النتيجة. يُنشر على قناة «ملفات سحابية جديدة» لأنها عالية الأهمية فيصل تنبيهها
     * إلى المستخدم وهو داخل تطبيق آخر، بينما قناة النقل منخفضة عمدًا (تقدّم مستمر لا يرنّ).
     */
    fun showResult(context: Context, snapshot: CloudTransferQueueSnapshot, completedItems: Int) {
        val text = CloudTransferQueueLogic.outcomeNotificationText(snapshot, completedItems) ?: return
        val notification = baseBuilder(context, "النقل من/إلى السحابة", text, NotificationChannels.CLOUD_FILES)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
        post(context, RESULT_ID, notification)
    }

    fun progress(context: Context, snapshot: CloudTransferQueueSnapshot, batch: CloudTransferBatch): Notification {
        val verb = if (batch.kind == CloudTransferKind.UPLOAD) "رفع" else "تنزيل"
        val total = snapshot.pending.size.coerceAtLeast(1)
        val done = snapshot.batches.count { it.status == CloudTransferStatus.DONE }
        val text = "جارٍ تنفيذ الدفعة الأولى من $total • اكتمل $done"
        val expanded = buildString {
            append(text).append('\n').append(snapshot.summary)
            if (batch.lastError.isNotBlank()) append("\nسبب التوقف: ${batch.lastError}")
        }
        return baseBuilder(context, "${batch.title} ($verb)", text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setProgress(total, done.coerceAtMost(total), false)
            .setOngoing(true)
            .addAction(0, if (snapshot.paused) "استئناف" else "إيقاف مؤقت",
                action(context, if (snapshot.paused) CloudTransferActionsReceiver.ACTION_RESUME else CloudTransferActionsReceiver.ACTION_PAUSE, REQUEST_PAUSE))
            .addAction(0, "إلغاء ما لم يبدأ", action(context, CloudTransferActionsReceiver.ACTION_CANCEL, REQUEST_CANCEL))
            .build()
    }

    /** إشعار يبقى بعد توقف العامل حتى يكون «استئناف» في متناول اليد من شاشة القفل */
    fun paused(context: Context, snapshot: CloudTransferQueueSnapshot): Notification =
        baseBuilder(context, "النقل موقوف مؤقتًا", snapshot.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "ما لم يبدأ محفوظ، وما اكتمل لن يُعاد. سيُستأنف التنزيل من حيث توقف."))
            .setOngoing(false)
            .setAutoCancel(true)
            .addAction(0, "استئناف", action(context, CloudTransferActionsReceiver.ACTION_RESUME, REQUEST_PAUSE))
            .build()

    fun show(context: Context, notification: Notification) = post(context, NOTIFICATION_ID, notification)

    /** بوابة واحدة للإذن: لا استثناءات أمنية ولا نشر صامت عند رفض الإشعارات من النظام */
    private fun post(context: Context, id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { manager.notify(id, notification) }
            .onFailure { Log.w(TAG, "تعذّر نشر إشعار النقل (المستخدم يراه في التطبيق)", it) }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun baseBuilder(
        context: Context,
        title: String,
        text: String,
        channelId: String = NotificationChannels.CLOUD_TRANSFERS
    ): NotificationCompat.Builder {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openPending)
    }

    private fun action(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CloudTransferActionsReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/**
 * كل أزرار طابور النقل — في مكان واحد، تستدعيه الواجهة ومستقبلُ الإشعار على السواء.
 *
 * «إيقاف مؤقت» كان يرفع راية يقرأها العامل **بين** الدفعتين فقط، فلا يلاحظها عنصرٌ طويل الجاري
 * الآن فيبدو الزر بلا عمل. المقاطعة الحقيقية هنا: [CloudSyncManager.cancelDownloads] يُلغي
 * وظيفة النقل القائمة (السجل نفسه يُستخدم لمسار التنزيل والرفع)، والملف الجزئي يبقى محفوظة
 * فيستأنف من نصفه بدل أن يُعاد من أوله.
 */
@Singleton
class CloudTransferControls @Inject constructor(
    @ApplicationContext private val context: Context,
    private val queue: CloudTransferQueueStore,
    private val scheduler: CloudSyncScheduler,
    private val manager: CloudSyncManager
) {

    /**
     * يُنشر فور قبول الطلب من أي شاشة حتى لا يبقى الإحساس بالنقل محصورًا داخل التطبيق:
     * النشر نفسه غير مربوط بقناة ولا بانتظار خدمة أمامية، والبناء في [CloudTransferNotifier].
     */
    suspend fun announceQueued() {
        CloudTransferNotifier.show(context, CloudTransferNotifier.queued(context, queue.snapshot()))
    }

    suspend fun pause() {
        queue.update { CloudTransferQueueLogic.setPaused(it, true) }
        manager.cancelDownloads()
        CloudTransferNotifier.show(context, CloudTransferNotifier.paused(context, queue.snapshot()))
    }

    suspend fun resume() {
        queue.update { CloudTransferQueueLogic.setPaused(it, false) }
        CloudTransferNotifier.cancel(context)
        scheduler.enqueueTransfers()
    }

    /** إلغاء ما لم يبدأ فقط؛ المكتمل لا يُمسّ، والفاشل يبقى للاستثناءات */
    suspend fun cancelQueued() {
        queue.update { CloudTransferQueueLogic.cancelPending(it) }
        manager.cancelDownloads()
        CloudTransferNotifier.cancel(context)
    }

    suspend fun retryFailed() {
        queue.update { CloudTransferQueueLogic.retryFailed(it) }
        CloudTransferNotifier.cancel(context)
        scheduler.enqueueTransfers()
    }

    suspend fun dismissFailed(batchId: String) {
        queue.update { CloudTransferQueueLogic.removeBatch(it, batchId) }
    }
}
