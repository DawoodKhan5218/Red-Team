package com.example.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.example.util.ToastUtils

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        ToastUtils.showSingleToast(context, "Enterprise Device Administration Activated")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        ToastUtils.showSingleToast(context, "Enterprise Device Administration Deactivated")
    }
}
