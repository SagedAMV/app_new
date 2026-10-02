package com.unihub.app.data.cloud

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * عامل المزامنة السحابية في الخلفية (@HiltWorker).
 * يعمل تلقائياً عند توفر اتصال بالإنترنت ليرسل التعديلات المحلية المعلّقة
 * إلى خادم Cloudflare R2 أو يفحص فهرس الملفات؛ لا ينزّل أي ملف ثقيل دون اختيار المستخدم.
 */
@HiltWorker
class CloudSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cloudSyncManager: CloudSyncManager,
    private val authManager: com.unihub.app.data.auth.CloudAuthManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        authManager.verifyActiveSessionWithCloud()
        if (!authManager.isAuthenticatedNow()) {
            return Result.success()
        }
        val mode = inputData.getString(KEY_MODE) ?: MODE_AUTO_SYNC
        val outcome = when (mode) {
            MODE_PUSH -> cloudSyncManager.syncWithServer(isManual = false)
            MODE_PULL -> cloudSyncManager.scanRemoteFilesAndSyncMetadata().map { "تم الفحص دون تنزيل" }
            else -> cloudSyncManager.syncWithServer(isManual = false)
        }

        return outcome.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                val msg = error.message.orEmpty()
                // إذا كانت بيانات الخادم غير مضبوطة بعد أو المستخدم غير مصرح له، ننهي العمل بهدوء دون إعادة محاولة عبثية
                if (msg.contains("غير مكتملة") || msg.contains("غير مضبوط") ||
                    msg.contains("تسجيل الدخول") || msg.contains("صلاحية") || msg.contains("موقوف")
                ) {
                    Result.success()
                } else {
                    Result.retry()
                }
            }
        )
    }

    companion object {
        const val KEY_MODE = "cloud_sync_mode"
        const val MODE_AUTO_SYNC = "auto_sync"
        const val MODE_PUSH = "push"
        const val MODE_PULL = "pull"
    }
}
