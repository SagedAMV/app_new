package com.unihub.app.data.backup

import androidx.room.InvalidationTracker
import com.unihub.app.core.prefs.AutoBackupPreferences
import com.unihub.app.core.prefs.CloudSyncPreferences
import com.unihub.app.data.cloud.CloudSyncManager
import com.unihub.app.data.local.UniHubDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * مراقب التعديلات المركزي على قاعدة البيانات:
 * 1) يشغّل النسخ الاحتياطي المحلي التلقائي عند تفعيل «نسخة بعد كل تعديل».
 * 2) يشغّل المزامنة السحابية مع خادم Cloudflare R2 (يرسل البيانات للخادم إن توفر
 *    الإنترنت، أو يحفظ راية الانتظار محلياً ليرسلها فور توفر الإنترنت).
 *
 * محصّن ضد الحلقات المفرغة: إذا كانت الكتابة في القاعدة ناتجة عن سحب نسخة من
 * الخادم ([BackupRepository.shouldIgnoreInvalidation])، يتجاهل المراقب الإشعار.
 */
@Singleton
class AutoBackupChangeWatcher @Inject constructor(
    private val database: UniHubDatabase,
    private val backupRepository: BackupRepository,
    private val preferences: AutoBackupPreferences,
    private val scheduler: AutoBackupScheduler,
    private val cloudSyncPreferences: CloudSyncPreferences,
    private val cloudSyncManager: CloudSyncManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var debounceJob: Job? = null
    private val started = AtomicBoolean(false)

    /** يُستدعى مرة واحدة عند إقلاع التطبيق */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        database.invalidationTracker.addObserver(
            object : InvalidationTracker.Observer(
                "folders", "files", "tasks", "notes", "exams", "lectures"
            ) {
                override fun onInvalidated(tables: Set<String>) {
                    if (backupRepository.shouldIgnoreInvalidation()) return
                    scheduleBackupAfterDebounce()
                }
            }
        )
    }

    private fun scheduleBackupAfterDebounce() {
        // نسجّل فوراً أن هناك تعديلاً محلياً جديداً حتى لو أُغلق التطبيق قبل انتهاء المهلة
        scope.launch {
            if (!backupRepository.shouldIgnoreInvalidation()) {
                cloudSyncPreferences.markLocalChange()
            }
        }
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MILLIS)
            if (backupRepository.shouldIgnoreInvalidation()) return@launch

            // 1) النسخ المحلي التلقائي عبر SAF (إن كان مفعلاً)
            val settings = preferences.snapshot()
            if (settings.backupOnChange && settings.isFolderConfigured) {
                scheduler.enqueueOnceLatest()
            }

            // 2) المزامنة السحابية مع خادم Cloudflare R2 (أونلاين / أوفلاين)
            cloudSyncManager.onLocalDataChanged()
        }
    }

    companion object {
        private const val DEBOUNCE_MILLIS = 5_000L
    }
}
