package com.unihub.app.data.backup

import androidx.room.InvalidationTracker
import com.unihub.app.core.prefs.AutoBackupPreferences
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
 * «نسخة بعد كل عملية تعديل» (طلب تعليمات.md — لتجربة أن النسخ يعمل فعلاً).
 *
 * التنفيذ مركزي هنا بدل نثر استدعاءات في كل مستودع: نراقب جداول القاعدة عبر
 * [InvalidationTracker] — أي كتابة على أي جدول تُشعرنا فوراً. ننتظر خمس ثوانٍ
 * من آخر تعديل (إزالة اهتزاز: الكتابة المتتابعة تُعامل كتعديل واحد) ثم نطلب
 * نسخة فورية فوق ملف «آخر نسخة» الثابت — بلا تراكم ملفات.
 *
 * آمن من الحلقات: النسخ التلقائي نفسه لا يكتب في القاعدة (يكتب في شجرة SAF
 * فقط) فلا يُعيد إشعار المراقب أبداً.
 */
@Singleton
class AutoBackupChangeWatcher @Inject constructor(
    private val database: UniHubDatabase,
    private val preferences: AutoBackupPreferences,
    private val scheduler: AutoBackupScheduler
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
                    scheduleBackupAfterDebounce()
                }
            }
        )
    }

    private fun scheduleBackupAfterDebounce() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MILLIS)
            val settings = preferences.snapshot()
            // الخيار مفعّل + المجلد محدد — وإلا لا معنى لأي نسخة
            if (settings.backupOnChange && settings.isFolderConfigured) {
                scheduler.enqueueOnceLatest()
            }
        }
    }

    companion object {
        private const val DEBOUNCE_MILLIS = 5_000L
    }
}
