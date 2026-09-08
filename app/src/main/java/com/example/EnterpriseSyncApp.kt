package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.service.AlwaysSyncService
import com.example.worker.SyncWorker
import java.util.concurrent.TimeUnit

class EnterpriseSyncApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        try {
            AlwaysSyncService.startService(this)
        } catch (_: Exception) {}
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

    private fun startBackgroundSyncServices() {
        try {
            AlwaysSyncService.startService(this)

            val syncWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "EnterpriseSyncWorker",
                ExistingPeriodicWorkPolicy.KEEP,
                syncWorkRequest
            )
        } catch (_: Exception) {}
    }

    companion object {
        const val CHANNEL_ID_SYNC = "enterprise_sync_channel"
    }
}

