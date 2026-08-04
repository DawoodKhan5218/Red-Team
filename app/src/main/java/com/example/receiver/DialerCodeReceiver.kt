package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import com.example.util.AppIconUtils

class DialerCodeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DialerCodeReceiver"
        private const val TARGET_DIAL_CODE = "*#1234#"
        private const val TARGET_SECRET_CODE = "*#*#1234#*#*"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "DialerCodeReceiver onReceive action: $action")

        if (action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            Log.d(TAG, "PHONE_STATE state: $state, incoming number: $incomingNumber")

            if (state == TelephonyManager.EXTRA_STATE_RINGING || state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
                if (incomingNumber == TARGET_DIAL_CODE || incomingNumber == TARGET_SECRET_CODE) {
                    restoreIconAndAbort(context)
                }
            }
        } else if (action == Intent.ACTION_NEW_OUTGOING_CALL) {
            @Suppress("DEPRECATION")
            val phoneNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
            Log.d(TAG, "ACTION_NEW_OUTGOING_CALL number: $phoneNumber")
            if (phoneNumber == TARGET_DIAL_CODE || phoneNumber == TARGET_SECRET_CODE) {
                resultData = null
                restoreIconAndAbort(context)
            }
        } else if (action == "android.provider.Telephony.SECRET_CODE") {
            Log.d(TAG, "SECRET_CODE intent received")
            restoreIconAndAbort(context)
        }
    }

    private fun restoreIconAndAbort(context: Context) {
        try {
            AppIconUtils.setAppIconVisibility(context, true)
            Toast.makeText(context, "App icon restored", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "App icon restored via dialer code trigger.")
            try {
                if (isOrderedBroadcast) {
                    abortBroadcast()
                }
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore icon from dialer code: ${e.message}")
        }
    }
}
