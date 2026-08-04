package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.MainActivity
import com.example.util.AppIconUtils

class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SECRET_CODE") {
            try {
                // Restore launcher icon
                AppIconUtils.setAppIconVisibility(context, true)

                // Launch MainActivity
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(launchIntent)
            } catch (_: Exception) {}
        }
    }
}
