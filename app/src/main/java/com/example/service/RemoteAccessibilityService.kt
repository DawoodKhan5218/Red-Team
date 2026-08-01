package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.example.data.sync.LostModeManager
import com.example.ui.LostDeviceActivity

class RemoteAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (LostModeManager.isLostModeActive(this)) {
            val eventType = event.eventType
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ) {
                val pkgName = event.packageName?.toString() ?: ""
                if (pkgName.isNotEmpty() && pkgName != packageName && !pkgName.contains("inputmethod")) {
                    LostModeManager.launchLostActivity(this)
                }
            }
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

        fun performAction(actionId: Int): Boolean {
            val service = instance ?: return false
            return service.performGlobalAction(actionId)
        }
    }
}
