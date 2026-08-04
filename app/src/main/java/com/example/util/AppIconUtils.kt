package com.example.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.example.MainActivity

object AppIconUtils {

    private const val TAG = "IconHelper"
    private const val ALIAS_CLASS_NAME = "com.example.MainActivityAlias"

    /**
     * Set the visibility of the app's launcher icon across launcher components.
     * Keeps MainActivity explicitly enabled for direct intent execution, while toggling
     * MainActivityAlias (the sole LAUNCHER component) to prevent duplicate launcher icons.
     * Uses PackageManager.DONT_KILL_APP to prevent killing the background service.
     */
    fun setAppIconVisibility(context: Context, visible: Boolean) {
        val aliasState = if (visible) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

        val pm = context.packageManager

        // Target MainActivityAlias (the single LAUNCHER category entry in manifest).
        // Do not alter MainActivity's component setting directly to avoid triggering duplicate fallback icons on custom ROMs (e.g. Vivo/MIUI).
        val aliasComponent = ComponentName(context.packageName, ALIAS_CLASS_NAME)
        try {
            pm.setComponentEnabledSetting(
                aliasComponent,
                aliasState,
                PackageManager.DONT_KILL_APP
            )
            Log.d(TAG, "MainActivityAlias icon visibility set to $visible using DONT_KILL_APP")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set visibility for MainActivityAlias: ${e.message}")
        }

        // Save stealth_mode preference
        try {
            val prefs = context.getSharedPreferences("admin_sync_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("stealth_mode", !visible).apply()
        } catch (_: Exception) {}
    }

    /**
     * Check if the launcher icon is currently visible.
     */
    fun isAppIconVisible(context: Context): Boolean {
        val aliasComponent = ComponentName(context.packageName, ALIAS_CLASS_NAME)
        val pm = context.packageManager
        val aliasSetting = pm.getComponentEnabledSetting(aliasComponent)

        return aliasSetting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
}
