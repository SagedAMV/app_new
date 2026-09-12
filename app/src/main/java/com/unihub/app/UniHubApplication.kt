package com.unihub.app

import android.app.Application
import com.unihub.app.data.local.DatabaseSelfHeal
import com.unihub.app.notifications.NotificationChannels
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class UniHubApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // قبل أن يلمس أي مكوّن قاعدة البيانات: فحص الملف الموجود وإعادة بنائه
        // إن كان من بِناء سابق بمخطط غير متوافق — يمنع كراش الإقلاع نهائياً.
        DatabaseSelfHeal.ensureHealthyDatabase(this)
        NotificationChannels.create(this)
    }
}
