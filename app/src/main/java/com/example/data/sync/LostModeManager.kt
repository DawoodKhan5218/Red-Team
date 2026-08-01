package com.example.data.sync

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.receiver.MyDeviceAdminReceiver
import com.example.service.RemoteAccessibilityService
import com.example.ui.LostDeviceActivity

object LostModeManager {

    const val ACTION_UNLOCK_LOST_MODE = "com.example.ACTION_UNLOCK_LOST_MODE"
    const val ACTION_SIREN_STATE = "com.example.ACTION_SIREN_STATE"
    const val EXTRA_SIREN_PLAY = "extra_siren_play"
    private const val LOST_MODE_NOTIFICATION_ID = 9999

    private const val PREFS_NAME = "lost_mode_prefs"
    private const val KEY_IS_LOST = "is_lost_mode_active"
    private const val KEY_TITLE = "custom_lost_title"
    private const val KEY_SUBTITLE = "custom_lost_subtitle"
    private const val KEY_MESSAGE = "custom_lost_message"
    private const val KEY_PHONE = "owner_phone"
    private const val KEY_EMAIL = "owner_email"
    private const val KEY_REWARD = "reward_note"
    private const val KEY_THEME = "overlay_theme"
    private const val KEY_ICON = "overlay_icon"
    private const val KEY_SHOW_SIREN = "show_siren_button"
    private const val KEY_SHOW_CONTACT = "show_owner_contact"
    private const val KEY_PIN = "unlock_pin"

    data class LostOverlayConfig(
        val title: String = "THIS PHONE IS LOST",
        val subtitle: String = "Device is remotely locked by the owner",
        val message: String = "This phone is lost. If found, please contact the owner immediately to return it.",
        val ownerPhone: String = "",
        val ownerEmail: String = "",
        val rewardNote: String = "",
        val theme: String = "Red Emergency",
        val icon: String = "Lock",
        val showSiren: Boolean = true,
        val showContact: Boolean = true,
        val pin: String = "1234"
    )

    fun isLostModeActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_IS_LOST, false)
    }

    fun getOverlayConfig(context: Context): LostOverlayConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return LostOverlayConfig(
            title = prefs.getString(KEY_TITLE, "THIS PHONE IS LOST") ?: "THIS PHONE IS LOST",
            subtitle = prefs.getString(KEY_SUBTITLE, "Device is remotely locked by the owner") ?: "Device is remotely locked by the owner",
            message = prefs.getString(KEY_MESSAGE, "This phone is lost. If found, please contact the owner immediately to return it.")
                ?: "This phone is lost. If found, please contact the owner immediately to return it.",
            ownerPhone = prefs.getString(KEY_PHONE, "") ?: "",
            ownerEmail = prefs.getString(KEY_EMAIL, "") ?: "",
            rewardNote = prefs.getString(KEY_REWARD, "") ?: "",
            theme = prefs.getString(KEY_THEME, "Red Emergency") ?: "Red Emergency",
            icon = prefs.getString(KEY_ICON, "Lock") ?: "Lock",
            showSiren = prefs.getBoolean(KEY_SHOW_SIREN, true),
            showContact = prefs.getBoolean(KEY_SHOW_CONTACT, true),
            pin = prefs.getString(KEY_PIN, "1234") ?: "1234"
        )
    }

    fun saveOverlayConfig(context: Context, config: LostOverlayConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_TITLE, config.title.ifBlank { "THIS PHONE IS LOST" })
            .putString(KEY_SUBTITLE, config.subtitle.ifBlank { "Device is remotely locked by the owner" })
            .putString(KEY_MESSAGE, config.message.ifBlank { "This phone is lost. If found, please contact the owner immediately to return it." })
            .putString(KEY_PHONE, config.ownerPhone.trim())
            .putString(KEY_EMAIL, config.ownerEmail.trim())
            .putString(KEY_REWARD, config.rewardNote.trim())
            .putString(KEY_THEME, config.theme)
            .putString(KEY_ICON, config.icon)
            .putBoolean(KEY_SHOW_SIREN, config.showSiren)
            .putBoolean(KEY_SHOW_CONTACT, config.showContact)
            .putString(KEY_PIN, config.pin.ifBlank { "1234" }.trim())
            .apply()
    }

    fun getCustomMessage(context: Context): String = getOverlayConfig(context).message
    fun getOwnerPhone(context: Context): String = getOverlayConfig(context).ownerPhone
    fun getUnlockPin(context: Context): String = getOverlayConfig(context).pin

    fun saveConfig(context: Context, message: String, phone: String, pin: String) {
        val current = getOverlayConfig(context)
        saveOverlayConfig(context, current.copy(message = message, ownerPhone = phone, pin = pin))
    }

    fun launchLostActivity(context: Context) {
        val intent = Intent(context, LostDeviceActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or 
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or 
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            LOST_MODE_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = "lost_mode_alert_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Lost Mode Emergency",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full screen lost mode overlay alert"
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Device Lost & Locked")
            .setContentText("Tap to view lost mode recovery screen")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(false)
            .setOngoing(true)

        notificationManager.notify(LOST_MODE_NOTIFICATION_ID, builder.build())

        try {
            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }.toBundle()
            } else null
            context.startActivity(intent, options)
        } catch (_: Exception) {
            // Full screen intent notification will display
        }
    }

    fun lockDeviceWithCustomMessage(
        context: Context,
        message: String? = null,
        phone: String? = null,
        pin: String? = null
    ): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val finalMsg = message?.ifBlank { null } ?: getCustomMessage(context)
        val finalPhone = phone?.ifBlank { null } ?: getOwnerPhone(context)
        val finalPin = pin?.ifBlank { null } ?: getUnlockPin(context)

        prefs.edit()
            .putBoolean(KEY_IS_LOST, true)
            .putString(KEY_MESSAGE, finalMsg)
            .putString(KEY_PHONE, finalPhone)
            .putString(KEY_PIN, finalPin)
            .apply()

        // Launch full screen overlay LostDeviceActivity via fullScreenIntent / ActivityOptions
        launchLostActivity(context)

        // Lock screen via DevicePolicyManager if active, or Accessibility Service
        var lockStatus = ""
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val adminComponent = ComponentName(context, MyDeviceAdminReceiver::class.java)

        if (dpm != null && dpm.isAdminActive(adminComponent)) {
            try {
                dpm.lockNow()
                lockStatus = "Locked hardware screen via Device Admin."
            } catch (e: Exception) {
                lockStatus = "Device Admin lock failed: ${e.message}"
            }
        }

        if (lockStatus.isEmpty()) {
            val accLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                RemoteAccessibilityService.performAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            } else false
            lockStatus = if (accLocked) "Locked screen via Accessibility Service." else "Screen lock trigger attempted (Lost mode screen overlay active)."
        }

        return "Lost Mode Activated! Custom message displayed on screen. $lockStatus"
    }

    fun unlockDevice(context: Context, enteredPin: String): Boolean {
        val actualPin = getUnlockPin(context)
        if (enteredPin.trim() == actualPin.trim() || enteredPin.trim() == "9999") { // Allow master unlock 9999
            return forceUnlockDevice(context)
        }
        return false
    }

    fun forceUnlockDevice(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_IS_LOST, false).apply()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            notificationManager.cancel(LOST_MODE_NOTIFICATION_ID)
        } catch (_: Exception) {}

        // Broadcast unlock signal to dismiss LostDeviceActivity
        val intent = Intent(ACTION_UNLOCK_LOST_MODE).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
        return true
    }

    fun triggerSiren(context: Context, play: Boolean) {
        val intent = Intent(ACTION_SIREN_STATE).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_SIREN_PLAY, play)
        }
        context.sendBroadcast(intent)
    }
}
