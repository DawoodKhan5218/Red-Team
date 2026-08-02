package com.example.receiver

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.util.ToastUtils

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        reapplyCameraPolicies(context)
        ToastUtils.showSingleToast(context, "Enterprise Device Administration Activated")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        ToastUtils.showSingleToast(context, "Enterprise Device Administration Deactivated")
    }

    companion object {
        fun reapplyCameraPolicies(context: Context): String {
            return try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                    ?: return "DevicePolicyManager unavailable"

                val admins = listOf(
                    ComponentName(context, MyDeviceAdminReceiver::class.java),
                    ComponentName(context, DeviceAdminReceiver::class.java)
                )

                var appliedCount = 0
                val results = StringBuilder()

                for (adminComponent in admins) {
                    if (dpm.isAdminActive(adminComponent)) {
                        dpm.setCameraDisabled(adminComponent, false)
                        val isCameraDisabled = dpm.getCameraDisabled(adminComponent)
                        appliedCount++
                        results.append("[${adminComponent.shortClassName}: cameraDisabled=$isCameraDisabled] ")
                        Log.d("MyDeviceAdminReceiver", "Re-applied setCameraDisabled(false) to ${adminComponent.className}, result=$isCameraDisabled")
                    }
                }

                if (appliedCount == 0) {
                    "No active Device Admin component registered."
                } else {
                    "Policy enforced: $results"
                }
            } catch (e: Exception) {
                Log.e("MyDeviceAdminReceiver", "Error re-applying camera policies", e)
                "Failed to reapply policy: ${e.message}"
            }
        }
    }
}

