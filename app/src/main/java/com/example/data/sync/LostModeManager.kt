package com.example.data.sync

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.receiver.MyDeviceAdminReceiver
import com.example.service.RemoteAccessibilityService
import com.example.ui.LostDeviceActivity

object LostModeManager {

    const val ACTION_UNLOCK_LOST_MODE = "com.example.ACTION_UNLOCK_LOST_MODE"
    const val ACTION_SIREN_STATE = "com.example.ACTION_SIREN_STATE"
    const val EXTRA_SIREN_PLAY = "extra_siren_play"

    private const val PREFS_NAME = "lost_mode_prefs"
    private const val KEY_IS_LOST = "is_lost_mode_active"
    private const val KEY_MESSAGE = "custom_lost_message"
    private const val KEY_PHONE = "owner_phone"
    private const val KEY_PIN = "unlock_pin"

    fun isLostModeActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_IS_LOST, false)
    }

    fun getCustomMessage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MESSAGE, "This phone is lost. If found, please contact the owner immediately to return it.") 
            ?: "This phone is lost. If found, please contact the owner immediately to return it."
    }

    fun getOwnerPhone(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PHONE, "") ?: ""
    }

    fun getUnlockPin(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PIN, "1234") ?: "1234"
    }

    fun saveConfig(context: Context, message: String, phone: String, pin: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_MESSAGE, message.ifBlank { "This phone is lost. If found, please contact the owner immediately to return it." })
            .putString(KEY_PHONE, phone.trim())
            .putString(KEY_PIN, pin.ifBlank { "1234" }.trim())
            .apply()
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

        // Launch full screen overlay LostDeviceActivity
        val intent = Intent(context, LostDeviceActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or 
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or 
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        context.startActivity(intent)

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
