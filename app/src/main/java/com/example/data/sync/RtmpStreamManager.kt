package com.example.data.sync

import android.content.Context
import android.util.Log
import com.pedro.rtmp.rtmp.RtmpClient
import com.pedro.rtmp.utils.ConnectCheckerRtmp

class RtmpStreamManager(private val context: Context) : ConnectCheckerRtmp {

    private var isStreaming = false
    private val rtmpClient: RtmpClient = RtmpClient(this)

    fun startStream(rtmpUrl: String) {
        if (isStreaming) {
            Log.w("RtmpStreamManager", "Stream is already active.")
            return
        }
        Log.d("RtmpStreamManager", "Connecting to RTMP ingest target: $rtmpUrl")
        try {
            rtmpClient.setReconnectionAttempts(3)
            rtmpClient.connect(rtmpUrl)
            isStreaming = true
        } catch (e: Exception) {
            Log.e("RtmpStreamManager", "Failed to start RTMP stream: ${e.message}")
            isStreaming = false
        }
    }

    fun stopStream() {
        if (!isStreaming) return
        Log.d("RtmpStreamManager", "Stopping live RTMP stream...")
        try {
            rtmpClient.disconnect()
        } catch (_: Exception) {}
        isStreaming = false
    }

    fun isStreamActive(): Boolean = isStreaming

    override fun onConnectionStartedRtmp(rtmpUrl: String) {
        Log.i("RtmpStreamManager", "RTMP connection started: $rtmpUrl")
    }

    override fun onConnectionSuccessRtmp() {
        Log.i("RtmpStreamManager", "RTMP connection successful! Stream is LIVE.")
    }

    override fun onConnectionFailedRtmp(reason: String) {
        Log.e("RtmpStreamManager", "RTMP connection failed: $reason")
        isStreaming = false
    }

    override fun onNewBitrateRtmp(bitrate: Long) {}
    
    override fun onDisconnectRtmp() {
        Log.i("RtmpStreamManager", "Disconnected from RTMP stream.")
        isStreaming = false
    }

    override fun onAuthErrorRtmp() {
        Log.e("RtmpStreamManager", "RTMP Authentication Error")
        isStreaming = false
    }

    override fun onAuthSuccessRtmp() {
        Log.i("RtmpStreamManager", "RTMP Authentication Success")
    }
}
