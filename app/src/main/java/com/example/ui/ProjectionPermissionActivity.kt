package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.data.sync.MediaProjectionHelper

class ProjectionPermissionActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ProjectionPermissionAct"
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.d(TAG, "MediaProjection permission granted by user.")
            try {
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                val projection = projectionManager?.getMediaProjection(result.resultCode, result.data!!)
                com.example.service.StealthCaptureService.activeMediaProjection = projection
                projection?.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        Log.w(TAG, "MediaProjection session stopped by OS.")
                        com.example.service.StealthCaptureService.activeMediaProjection = null
                    }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            } catch (e: Exception) {
                Log.e(TAG, "Error getting media projection in activity: ${e.message}")
            }
            MediaProjectionHelper.saveProjectionToken(this, result.resultCode, result.data!!)
            MediaProjectionHelper.startService(this)
        } else {
            Log.w(TAG, "MediaProjection permission denied or canceled.")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (projectionManager != null) {
            try {
                captureLauncher.launch(projectionManager.createScreenCaptureIntent())
            } catch (e: Exception) {
                Log.e(TAG, "Error launching screen capture intent: ${e.message}")
                finish()
            }
        } else {
            finish()
        }
    }
}
