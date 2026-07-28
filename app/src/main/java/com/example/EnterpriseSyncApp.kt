package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class EnterpriseSyncApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_SYNC,
                "Enterprise Sync Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing cloud synchronization and telemetry monitoring"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID_SYNC = "enterprise_sync_channel"
    }
}
