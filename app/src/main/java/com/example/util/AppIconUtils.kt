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
     * Uses PackageManager.DONT_KILL_APP to prevent killing the background service.
     */
    fun setAppIconVisibility(context: Context, visible: Boolean) {
        val state = if (visible) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

        val pm = context.packageManager

        // 1. Ensure MainActivity itself stays ALWAYS ENABLED so the app process remains runnable
        val mainComponent = ComponentName(context, MainActivity::class.java)
        try {
            pm.setComponentEnabledSetting(
                mainComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d(TAG, "MainActivity explicitly kept ENABLED for direct intent execution.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure MainActivity enabled state: ${e.message}")
        }

        // 2. Target MainActivityAlias (which holds CATEGORY_LAUNCHER)
        val aliasComponent = ComponentName(context.packageName, ALIAS_CLASS_NAME)
        try {
            pm.setComponentEnabledSetting(
                aliasComponent,
                state,
                PackageManager.DONT_KILL_APP
            )
            val currentState = pm.getComponentEnabledSetting(aliasComponent)
            Log.d(TAG, "MainActivityAlias launcher icon set to visible=$visible, current state: $currentState")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set visibility for MainActivityAlias: ${e.message}")
        }

        // 3. Dynamically discover and update any other LAUNCHER category components
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(context.packageName)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_DISABLED_COMPONENTS)
            for (ri in resolveInfos) {
                val comp = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
                try {
                    pm.setComponentEnabledSetting(comp, state, PackageManager.DONT_KILL_APP)
                    Log.d(TAG, "Updated LAUNCHER component ${ri.activityInfo.name} state to $state")
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating LAUNCHER component ${ri.activityInfo.name}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying launcher components: ${e.message}")
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
        val setting = pm.getComponentEnabledSetting(aliasComponent)

        return if (setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(context.packageName)
            }
            val list = pm.queryIntentActivities(mainIntent, 0)
            list.isNotEmpty()
        } else {
            setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
    }
}
