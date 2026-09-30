package com.unihub.app.data.cloud

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * جدولة المزامنة السحابية مع Cloudflare R2 عبر WorkManager.
 *
 * جميع الأعمال هنا مقيدة بشرط توفر اتصال بالإنترنت ([NetworkType.CONNECTED]):
 * إذا عدّل المستخدم بياناته وهو أوفلاين، تبقى الجدولة معلّقة لدى النظام،
 * وبمجرد توفر الإنترنت ينفّذ النظام [CloudSyncWorker] تلقائياً.
 */
@Singleton
class CloudSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    private val networkConstraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * جدولة مزامنة فورية بمجرد توفر الإنترنت.
     */
    fun enqueueSyncWhenConnected(mode: String = CloudSyncWorker.MODE_AUTO_SYNC) {
        val data = Data.Builder()
            .putString(CloudSyncWorker.KEY_MODE, mode)
            .build()
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setConstraints(networkConstraints)
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            ONCE_CLOUD_SYNC_WORK,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /**
     * جدولة فحص ومزامنة دورية كل 15 دقيقة (قد يؤخره النظام لتوفير البطارية) عند توفر الإنترنت.
     */
    fun ensurePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_CLOUD_SYNC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(ONCE_CLOUD_SYNC_WORK)
        workManager.cancelUniqueWork(PERIODIC_CLOUD_SYNC_WORK)
    }

    companion object {
        const val ONCE_CLOUD_SYNC_WORK = "cloud_r2_sync_once"
        const val PERIODIC_CLOUD_SYNC_WORK = "cloud_r2_sync_periodic"
    }
}
