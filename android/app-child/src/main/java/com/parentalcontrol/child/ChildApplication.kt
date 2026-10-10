package com.parentalcontrol.child

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.parentalcontrol.core.CrtNativeLoader

class ChildApplication : Application() {

    companion object {
        const val CHANNEL_ID_PERSISTENT = "crt_persistent_service"
        const val CHANNEL_NAME_PERSISTENT = "Parental Protection Service"
        lateinit var instance: ChildApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrtNativeLoader.load()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_PERSISTENT,
                CHANNEL_NAME_PERSISTENT,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps parental protection active in background"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
