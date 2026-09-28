package com.unihub.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.unihub.app.data.backup.AutoBackupChangeWatcher
import com.unihub.app.data.backup.AutoBackupExporter
import com.unihub.app.data.local.DatabaseSelfHeal
import com.unihub.app.notifications.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class UniHubApplication : Application(), Configuration.Provider {

    /** مصنع عمال Hilt — ضروري لعمال النسخ الاحتياطي التلقائي (@HiltWorker) */
    @Inject lateinit var workerFactory: HiltWorkerFactory

    /** مراقب التعديلات: يطلق نسخة احتياطية بعد كل تعديل عند تفعيل الخيار */
    @Inject lateinit var autoBackupChangeWatcher: AutoBackupChangeWatcher

    @Inject lateinit var autoBackupExporter: AutoBackupExporter

    /** نطاق إقلاع خفيف لفحوصات الخلفية غير الحرجة — لا يعطل الإقلاع أبداً */
    private val bootScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // قبل أن يلمس أي مكوّن قاعدة البيانات: فحص الملف الموجود وإعادة بنائه
        // إن كان من بِناء سابق بمخطط غير متوافق — يمنع كراش الإقلاع نهائياً.
        DatabaseSelfHeal.ensureHealthyDatabase(this)
        NotificationChannels.create(this)
        // يبدأ المراقب بالتقاط تغييرات القاعدة (إن كان خيار «نسخ بعد كل تعديل» مفعلاً)
        autoBackupChangeWatcher.start()
        // تحقق إقلاع للنسخ التلقائي: إن كان المجلد المحدد قد فقد الوصول إليه
        // تظهر رسالة واضحة في الإعدادات فوراً بدل انتظار أول محاولة لتفشل
        // (تحصين جلسة إصلاح «النسخ التلقائي لا يعمل» — راجع تعليمات.md)
        bootScope.launch {
            runCatching { autoBackupExporter.validateConfiguredFolder() }
        }
    }

    /**
     * يسلّم WorkManager مصنع عمال Hilt — بدونه لا يستطيع إنشاء عمال
     * @HiltWorker المعتمدة على مستودعات محقونة. المهيّئ الافتراضي لـ
     * WorkManager يكتشف هذه الواجهة تلقائياً ويستخدمها.
     *
     * مُنفَّذة كخاصية Kotlin (لا كدالة getWorkManagerConfiguration) لأن نسخة
     * work-runtime المحلولة على الـ classpath تعرّف Configuration.Provider
     * بخاصية val — وصيغة الخاصية تتوافق مع التعريفين القديم (Java getter)
     * والحديث (Kotlin val) معاً، فلا ينكسر البناء عند تغيّر الإصدار.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
