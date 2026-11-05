package com.tacresq.app

import android.app.Application
import com.tacresq.app.notifications.PushNotificationChannels

class TacResqApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        PushNotificationChannels.ensure(this)
    }
}
