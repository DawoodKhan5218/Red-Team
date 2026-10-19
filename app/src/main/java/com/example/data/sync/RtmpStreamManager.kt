package com.example.data.sync

import android.content.Context
import android.view.SurfaceView
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraOpenException
import com.pedro.library.rtmp.RtmpCamera1

class RtmpStreamManager(context: Context, surfaceView: SurfaceView?) : ConnectChecker {

    private val rtmpCamera1: RtmpCamera1 = if (surfaceView != null) {
        RtmpCamera1(surfaceView, this)
    } else {
        RtmpCamera1(context, this, true)
    }

    fun startStream(url: String) {
        try {
            if (!rtmpCamera1.isStreaming) {
                if (rtmpCamera1.prepareAudio() && rtmpCamera1.prepareVideo()) {
                    rtmpCamera1.startStream(url)
                }
            }
        } catch (e: CameraOpenException) {
            // Handle camera exception
        }
    }

    fun stopStream() {
        if (rtmpCamera1.isStreaming) {
            rtmpCamera1.stopStream()
        }
    }

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {}
    override fun onConnectionFailed(reason: String) {}
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {}
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
}
