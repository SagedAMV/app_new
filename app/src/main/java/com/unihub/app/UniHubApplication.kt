package com.unihub.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.unihub.app.data.backup.AutoBackupChangeWatcher
import com.unihub.app.data.local.DatabaseSelfHeal
import com.unihub.app.notifications.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class UniHubApplication : Application(), Configuration.Provider {

    /** مصنع عمال Hilt — ضروري لعمال النسخ الاحتياطي التلقائي (@HiltWorker) */
    @Inject lateinit var workerFactory: HiltWorkerFactory

    /** مراقب التعديلات: يطلق نسخة احتياطية بعد كل تعديل عند تفعيل الخيار */
    @Inject lateinit var autoBackupChangeWatcher: AutoBackupChangeWatcher

    override fun onCreate() {
        super.onCreate()
        // قبل أن يلمس أي مكوّن قاعدة البيانات: فحص الملف الموجود وإعادة بنائه
        // إن كان من بِناء سابق بمخطط غير متوافق — يمنع كراش الإقلاع نهائياً.
        DatabaseSelfHeal.ensureHealthyDatabase(this)
        NotificationChannels.create(this)
        // يبدأ المراقب بالتقاط تغييرات القاعدة (إن كان خيار «نسخ بعد كل تعديل» مفعلاً)
        autoBackupChangeWatcher.start()
    }

    /**
     * يسلّم WorkManager مصنع عمال Hilt — بدونه لا يستطيع إنشاء عمال
     * @HiltWorker المعتمدة على مستودعات محقونة. المهيّئ الافتراضي لـ
     * WorkManager يكتشف هذه الواجهة تلقائياً ويستخدمها.
     */
    override fun getWorkManagerConfiguration(): Configuration =
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
