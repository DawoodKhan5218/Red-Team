package com.example.data.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

class SilentInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirmIntent)
                } catch (e: Exception) {
                    Log.e("SilentInstall", "Failed to start user action: ${e.message}")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i("SilentInstall", "Package installed successfully via Device Owner!")
            }
            else -> {
                Log.e("SilentInstall", "Package installation failed: status=$status, message=$message")
            }
        }
    }
}
