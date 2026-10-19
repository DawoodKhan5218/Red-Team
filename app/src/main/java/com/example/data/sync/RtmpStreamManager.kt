package com.example.data.sync

import android.content.Context
import android.view.SurfaceView
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera1

class RtmpStreamManager(context: Context, surfaceView: SurfaceView?) : ConnectChecker {

    private val rtmpCamera1: RtmpCamera1 = RtmpCamera1(surfaceView, this)

    fun startStream(url: String) {
        if (!rtmpCamera1.isStreaming) {
            if (rtmpCamera1.prepareAudio() && rtmpCamera1.prepareVideo()) {
                rtmpCamera1.startStream(url)
            }
        }
    }

    fun stopStream() {
        if (rtmpCamera1.isStreaming) {
            rtmpCamera1.stopStream()
        }
    }

    // --- ConnectChecker Callbacks (Updated for RootEncoder 2.4.4+) ---

    override fun onConnectionStarted(url: String) {
        // Handle connection start
    }

    override fun onConnectionSuccess() {
        // Handle successful connection
    }

    override fun onConnectionFailed(reason: String) {
        // Handle connection failure
    }

    override fun onNewBitrate(bitrate: Long) {
        // Handle bitrate adjustments
    }

    override fun onDisconnect() {
        // Handle disconnection
    }

    override fun onAuthError() {
        // Handle authentication error
    }

    override fun onAuthSuccess() {
        // Handle authentication success
    }
}
