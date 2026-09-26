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
 * سياسة الفشل: إن لم يكن المجلد محدداً نعيد نجاحاً صامتاً (لا شيء لنفعله،
 * ولا نريد إعادة جدولة عبثية)؛ أما أخطاء الكتابة الفعلية فتعيد فشلاً مع
 * تسجيل السبب في التفضيلات ليظهر في شاشة الإعدادات.
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
                if (error.message?.contains("لم يتم تحديد مجلد") == true) {
                    // المجلد غير محدد بعد — لا فائدة من إعادة المحاولة
                    Result.success()
                } else {
                    Result.failure()
                }
            }
        )
    }

    companion object {
        const val KEY_MODE = "auto_backup_mode"
        const val MODE_LATEST = "latest"
    }
}
