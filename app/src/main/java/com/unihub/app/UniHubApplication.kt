package com.unihub.app

import android.app.Application
import com.unihub.app.notifications.NotificationChannels
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class UniHubApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
    }
}
