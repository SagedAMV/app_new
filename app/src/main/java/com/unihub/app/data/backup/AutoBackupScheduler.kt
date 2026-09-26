package com.unihub.app.data.backup

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * جدولة النسخ الاحتياطي التلقائي عبر WorkManager — نفس نهج [ReminderScheduler]:
 * أعمال فريدة بأسماء مستقرة يسهل إلغاؤها أو استبدالها عند تغيير الإعدادات.
 *
 * نمطان:
 *  - دوري: نسخة كل (يوم/3 أيام/أسبوع/شهر/مخصص) باسم ملف يحمل طابعاً زمنياً.
 *  - فوري (مرة واحدة): يكتب فوق «آخر نسخة» الثابتة — يستخدمه خيار «نسخة بعد كل
 *    عملية تعديل» وزر «نسخ الآن» حتى لا تتراكم ملفات التجربة.
 */
@Singleton
class AutoBackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * إعادة تطبيق الجدولة الدورية وفق الفاصل الجديد. أي قيمة دون يوم واحد
     * تعني الإلغاء الكامل (لا يوجد فاصل أدنى من الحد الدوري لـ WorkManager
     * يحتاجه هذا التطبيق).
     */
    fun applyPeriodic(intervalDays: Int) {
        if (intervalDays < 1) {
            cancelPeriodic()
            return
        }
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(
            intervalDays.toLong(), TimeUnit.DAYS
        )
            // أول نسخة بعد خمس دقائق من الجدولة — لا فوراً حتى لا يفاجأ المستخدم
            .setInitialDelay(5, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelPeriodic() {
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    /** نسخة فورية واحدة فوق ملف «آخر نسخة» — للتجربة ولزر «نسخ الآن» */
    fun enqueueOnceLatest() {
        val data = Data.Builder()
            .putString(AutoBackupWorker.KEY_MODE, AutoBackupWorker.MODE_LATEST)
            .build()
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setInputData(data)
            .build()
        workManager.enqueueUniqueWork(ONCE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val PERIODIC_WORK_NAME = "auto_backup_periodic"
        const val ONCE_WORK_NAME = "auto_backup_once"
    }
}
