package com.example.data.sync

import android.content.Context
import android.media.AudioManager
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

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}

        val audioSources = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.CAMCORDER
        )

        var recorder: MediaRecorder? = null
        var isRecordingStarted = false
        var lastException: Exception? = null

        for (source in audioSources) {
            try {
                val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }

                rec.setAudioSource(source)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioSamplingRate(44100)
                rec.setAudioEncodingBitRate(128000)
                rec.setOutputFile(outFile?.absolutePath)

                rec.prepare()
                rec.start()
                recorder = rec
                isRecordingStarted = true
                break
            } catch (e: Exception) {
                lastException = e
                try {
                    recorder?.release()
                } catch (_: Exception) {}
                recorder = null
            }
        }

        if (!isRecordingStarted || recorder == null) {
            throw Exception("Audio recording failed to start with any audio source: ${lastException?.message}")
        }

        try {
            delay(finalDuration * 1000L)
        } catch (e: Exception) {
            // delay cancelled or interrupted
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

