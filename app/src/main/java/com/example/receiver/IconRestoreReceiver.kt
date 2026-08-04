package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.example.util.AppIconUtils

class IconRestoreReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "IconRestoreReceiver"
        const val ACTION_SHOW_ICON = "com.your.app.SHOW_ICON"
        const val ACTION_SHOW_ICON_ALT = "com.example.SHOW_ICON"
        const val ACTION_SHOW_APP_ICON = "com.example.SHOW_APP_ICON"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "IconRestoreReceiver received action: $action")
        if (action == ACTION_SHOW_ICON || action == ACTION_SHOW_ICON_ALT || action == ACTION_SHOW_APP_ICON) {
            try {
                AppIconUtils.setAppIconVisibility(context, true)
                Toast.makeText(context, "App icon restored", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "App icon restored successfully via broadcast.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore app icon via broadcast: ${e.message}")
            }
        }
    }
}
