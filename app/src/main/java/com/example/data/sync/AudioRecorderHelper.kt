package com.example.data.sync

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

class AudioRecorderHelper(private val context: Context) {
    private var outFile: File? = null

    suspend fun startRecording(durationSec: Int = 10): String? = withContext(Dispatchers.IO) {
        val finalDuration = durationSec.coerceIn(3, 60)
        outFile = File(context.cacheDir, "remote_audio_${System.currentTimeMillis()}.m4a")
        if (outFile?.exists() == true) {
            outFile?.delete()
        }

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        var isRecordingStarted = false

        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC) // CRITICAL
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44100)
            recorder.setAudioEncodingBitRate(128000)
            recorder.setOutputFile(outFile?.absolutePath)

            recorder.prepare()
            recorder.start()
            isRecordingStarted = true

            delay(finalDuration * 1000L)
        } catch (e: Exception) {
            throw Exception("Audio recording error: ${e.message}")
        } finally {
            if (isRecordingStarted) {
                try {
                    recorder.stop()
                } catch (_: Throwable) {}
            }
            try {
                recorder.reset()
            } catch (_: Throwable) {}
            try {
                recorder.release()
            } catch (_: Throwable) {}
        }

        val file = outFile
        if (file != null && file.exists() && file.length() > 0) {
            val audioBytes = file.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
            return@withContext "data:audio/mp4;base64,$base64Audio"
        } else {
            throw Exception("Audio recording failed or output file empty.")
        }
    }

    fun cleanup() {
        try {
            if (outFile?.exists() == true) {
                outFile?.delete()
            }
        } catch (_: Exception) {}
    }
}
