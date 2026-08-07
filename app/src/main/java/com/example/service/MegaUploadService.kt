package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.data.sync.MegaUploadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MegaUploadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "MegaUploadService"
        private const val NOTIFICATION_ID = 98765
        private const val CHANNEL_ID = "media_sync_channel_silent"

        const val ACTION_START_BACKUP = "com.example.service.ACTION_START_BACKUP"
        const val ACTION_STOP_BACKUP = "com.example.service.ACTION_STOP_BACKUP"
        const val EXTRA_FILTER_TYPE = "EXTRA_FILTER_TYPE"

        fun startService(context: Context, filterType: String = "all") {
            val intent = Intent(context, MegaUploadService::class.java).apply {
                action = ACTION_START_BACKUP
                putExtra(EXTRA_FILTER_TYPE, filterType)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MegaUploadService::class.java).apply {
                action = ACTION_STOP_BACKUP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildSilentNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_BACKUP

        when (action) {
            ACTION_STOP_BACKUP -> {
                Log.d(TAG, "Stopping MegaUploadService...")
                MegaUploadManager.stopBackup(applicationContext)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START_BACKUP -> {
                val filterType = intent?.getStringExtra(EXTRA_FILTER_TYPE) ?: "all"
                Log.d(TAG, "Starting MegaUploadService with filter: $filterType")
                
                serviceScope.launch {
                    try {
                        MegaUploadManager.startBackup(applicationContext, filterType)
                    } finally {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Sync",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background file synchronization"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildSilentNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Syncing media")
            .setContentText("Background media synchronization in progress")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSound(null)
            .setVibrate(null)
            .build()
    }
}
