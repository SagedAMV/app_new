package com.unihub.app.notifications

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.unihub.app.core.common.DateFormats
import com.unihub.app.data.local.entity.ExamEntity
import com.unihub.app.data.local.entity.TaskEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * جدولة التذكيرات عبر WorkManager (أعمال فريدة بأسماء مستقرة يسهل إلغاؤها).
 * التحسين عن المرجع: منطق واحد مشترك [enqueue] بدل تكرار البناء في كل حالة،
 * وأسماء أعمال مشتقة من (النوع + المعرف + اللحظة) بحيث لا تتصادم ولا تُنسى عند الإلغاء.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** تذكيرات امتحان: قبل يوم، قبل ساعة، وعند البدء (كلٌّ فقط إن كان مستقبله قائماً) */
    fun scheduleExamReminders(exam: ExamEntity) {
        val start = DateFormats.dateTimeOf(exam.date, exam.time.ifBlank { null }) ?: return
        if (!start.isAfter(LocalDateTime.now())) return

        enqueue(
            workName = examWorkName(exam.id, "day"),
            title = "امتحان غداً 📖",
            message = "${exam.subject} — ${exam.type.label}${exam.room.ifBlank { "" }.let { if (it.isNotBlank()) " في قاعة $it" else "" }}",
            at = start.minusDays(1),
            channel = NotificationChannels.EXAMS
        )
        enqueue(
            workName = examWorkName(exam.id, "hour"),
            title = "امتحان بعد ساعة ⏰",
            message = "${exam.subject} — ${exam.type.label}",
            at = start.minusHours(1),
            channel = NotificationChannels.EXAMS
        )
        enqueue(
            workName = examWorkName(exam.id, "now"),
            title = "الامتحان الآن",
            message = exam.subject,
            at = start,
            channel = NotificationChannels.EXAMS
        )
    }

    fun cancelExamReminders(examId: Long) {
        listOf("day", "hour", "now").forEach {
            workManager.cancelUniqueWork(examWorkName(examId, it))
        }
    }

    /** تذكير مهمة: في يوم الاستحقاق 9 صباحاً */
    fun scheduleTaskReminder(task: TaskEntity) {
        val due = DateFormats.parseDateOrNull(task.dueDate) ?: return
        val at = due.atTime(9, 0)
        enqueue(
            workName = taskWorkName(task.id),
            title = "مهمة مستحقة اليوم ✅",
            message = task.title,
            at = at,
            channel = NotificationChannels.TASKS
        )
    }

    fun cancelTaskReminder(taskId: Long) {
        workManager.cancelUniqueWork(taskWorkName(taskId))
    }

    /** إلغاء كل التذكيرات (يُستخدم بعد استيراد نسخة احتياطية لإعادة الجدولة النظيفة) */
    fun cancelAll() {
        workManager.cancelAllWork()
    }

    private fun enqueue(
        workName: String,
        title: String,
        message: String,
        at: LocalDateTime,
        channel: String
    ) {
        val delay = Duration.between(LocalDateTime.now(), at).toMillis()
        if (delay <= 0) return

        val data = Data.Builder()
            .putString(ReminderWorker.KEY_TITLE, title)
            .putString(ReminderWorker.KEY_MESSAGE, message)
            .putString(ReminderWorker.KEY_CHANNEL, channel)
            .putInt(ReminderWorker.KEY_NOTIFICATION_ID, workName.hashCode().takeIf { it != 0 } ?: 1)
            .build()

        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .build()

        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
    }

    private fun examWorkName(examId: Long, slot: String) = "reminder_exam_${examId}_$slot"
    private fun taskWorkName(taskId: Long) = "reminder_task_$taskId"
}
