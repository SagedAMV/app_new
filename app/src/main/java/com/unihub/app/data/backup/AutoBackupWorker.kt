package com.unihub.app.data.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * عامل النسخ الاحتياطي التلقائي. يُحقن بالمستودعات عبر Hilt (@HiltWorker) —
 * يتطلب مصنع [androidx.hilt.work.HiltWorkerFactory] الممرَّر لـ WorkManager في
 * [com.unihub.app.UniHubApplication].
 *
 * سياسة الفشل (تحصين هذه الجلسة):
 *  - المجلد غير محدد: نجاح صامت (لا شيء لنفعله، ولا نريد إعادة جدولة عبثية).
 *  - [BackupAccessException] (فقد إذن الوصول للمجلد): فشل نهائي — إعادة
 *    المحاولة عبث حتى يعيد المستخدم اختيار المجلد، والسبب مسجّل في
 *    التفضيلات ويظهر في شاشة الإعدادات برسالة إرشادية.
 *  - أي خطأ إدخال/إخراج آخر: [Result.retry] — خطأ عابر يستحق محاولة أخرى
 *    تلقائية بدل إعلان الموت، وهذا أحد جذور مشكلة «لا يعمل أبداً» سابقاً.
 */
@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val exporter: AutoBackupExporter
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val latest = inputData.getString(KEY_MODE) == MODE_LATEST
        val result = exporter.runBackup(latest)
        return result.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                when {
                    // المجلد غير محدد بعد — لا فائدة من إعادة المحاولة
                    error.message?.contains("لم يتم تحديد مجلد") == true -> Result.success()
                    // إذن الوصول ضائع — فشل نهائي حتى يعيد المستخدم اختيار المجلد
                    error is BackupAccessException -> Result.failure()
                    // خطأ عابر (كتابة/فتح ملف) — يستحق إعادة محاولة تلقائية
                    else -> Result.retry()
                }
            }
        )
    }

    companion object {
        const val KEY_MODE = "auto_backup_mode"
        const val MODE_LATEST = "latest"
    }
}
