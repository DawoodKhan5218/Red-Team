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
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (LostModeManager.isLostModeActive(this)) {
                val pkg = event.packageName?.toString()
                if (pkg != null && pkg != packageName) {
                    // Force close system dialogs/shade and bring back LostDeviceActivity
                    @Suppress("DEPRECATION")
                    try {
                        sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                    } catch (_: Exception) {}

                    val intent = Intent(this, LostDeviceActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    }
                    startActivity(intent)
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
