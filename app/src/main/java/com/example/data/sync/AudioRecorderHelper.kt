package com.example.data.sync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class AudioRecorderHelper(private val context: Context) {
    private var outFile: File? = null

    suspend fun startRecording(durationSec: Int = 10): String? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("AudioRecorderHelper", "RECORD_AUDIO permission is not granted.")
            throw SecurityException("RECORD_AUDIO permission not granted. Please grant microphone permission in the app.")
        }

        val finalDuration = durationSec.coerceIn(3, 60)
        outFile = File(context.cacheDir, "remote_audio_${System.currentTimeMillis()}.amr")
        if (outFile?.exists() == true) {
            outFile?.delete()
        }

        var wakeLock: android.os.PowerManager.WakeLock? = null
        var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "EnterpriseSync::AudioRecordWakeLock")
            wakeLock?.acquire((finalDuration + 5) * 1000L)
            
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            wifiLock = wm?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "EnterpriseSync::AudioWifiLock")
            wifiLock?.acquire()
        } catch (_: Exception) {}

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.isMicrophoneMute = false
            audioManager?.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}

        // Strategy 1: MediaRecorder with multiple sources and formats
        var base64Result: String? = null
        try {
            base64Result = tryMediaRecorder(finalDuration)
        } catch (e: Exception) {
            Log.w("AudioRecorderHelper", "MediaRecorder failed: ${e.message}, trying AudioRecord fallback")
        }

        // Strategy 2: Low-level AudioRecord PCM capture (WAV) if MediaRecorder failed
        if (base64Result == null) {
            try {
                base64Result = tryAudioRecordPcm(finalDuration)
            } catch (e: Exception) {
                Log.e("AudioRecorderHelper", "AudioRecord PCM fallback failed: ${e.message}")
            }
        }

        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}

        if (base64Result != null) {
            return@withContext base64Result
        } else {
            throw Exception("Audio recording failed across MediaRecorder and AudioRecord PCM fallback engines.")
        }
    }

    private suspend fun tryMediaRecorder(durationSec: Int): String? {
        val audioSources = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.DEFAULT
        )

        var recorder: MediaRecorder? = null
        var isRecordingStarted = false
        var lastException: Exception? = null

        for (source in audioSources) {
            // Try AMR_NB first, then AAC (MPEG_4)
            val formatConfigs = listOf(
                Pair(MediaRecorder.OutputFormat.THREE_GPP, MediaRecorder.AudioEncoder.AMR_NB),
                Pair(MediaRecorder.OutputFormat.MPEG_4, MediaRecorder.AudioEncoder.AAC)
            )

            for ((outputFormat, audioEncoder) in formatConfigs) {
                try {
                    val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        MediaRecorder(context)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaRecorder()
                    }

                    rec.setAudioSource(source)
                    rec.setOutputFormat(outputFormat)
                    rec.setAudioEncoder(audioEncoder)
                    if (audioEncoder == MediaRecorder.AudioEncoder.AAC) {
                        rec.setAudioSamplingRate(44100)
                        rec.setAudioEncodingBitRate(128000)
                    } else {
                        rec.setAudioSamplingRate(8000)
                        rec.setAudioEncodingBitRate(12200)
                    }
                    rec.setOutputFile(outFile?.absolutePath)

                    rec.prepare()
                    rec.start()
                    recorder = rec
                    isRecordingStarted = true
                    Log.d("AudioRecorderHelper", "MediaRecorder started successfully with source=$source, format=$outputFormat, encoder=$audioEncoder")
                    break
                } catch (e: Exception) {
                    lastException = e
                    try { recorder?.release() } catch (_: Exception) {}
                    recorder = null
                }
            }
            if (isRecordingStarted) break
        }

        if (!isRecordingStarted || recorder == null) {
            throw lastException ?: Exception("MediaRecorder failed to start")
        }

        try {
            delay(durationSec * 1000L)
        } catch (_: Exception) {
        } finally {
            try { recorder.stop() } catch (_: Throwable) {}
            try { recorder.reset() } catch (_: Throwable) {}
            try { recorder.release() } catch (_: Throwable) {}
        }

        val file = outFile
        if (file != null && file.exists() && file.length() > 200) {
            val audioBytes = file.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
            val mimeType = if (file.name.endsWith(".3gp", true)) "audio/3gpp" else "audio/mp4"
            return "data:$mimeType;base64,$base64Audio"
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun tryAudioRecordPcm(durationSec: Int): String? {
        val sampleRates = listOf(16000, 8000, 44100, 22050)
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val audioSources = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.DEFAULT
        )

        var audioRecord: AudioRecord? = null
        var chosenSampleRate = 16000
        var bufferSize = 4096

        for (sampleRate in sampleRates) {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize > 0) {
                val calculatedSize = (minBufferSize * 2).coerceAtLeast(8192)
                for (source in audioSources) {
                    try {
                        val record = AudioRecord(source, sampleRate, channelConfig, audioFormat, calculatedSize)
                        if (record.state == AudioRecord.STATE_INITIALIZED) {
                            audioRecord = record
                            chosenSampleRate = sampleRate
                            bufferSize = calculatedSize
                            Log.d("AudioRecorderHelper", "AudioRecord initialized with source=$source, sampleRate=$sampleRate")
                            break
                        } else {
                            record.release()
                        }
                    } catch (_: Exception) {}
                }
                if (audioRecord != null) break
            }
        }

        if (audioRecord == null) {
            Log.e("AudioRecorderHelper", "AudioRecord failed to initialize across all sample rates and sources.")
            return null
        }

        val wavFile = File(context.cacheDir, "remote_audio_${System.currentTimeMillis()}.wav")
        val pcmDataStream = ByteArrayOutputStream()

        try {
            audioRecord.startRecording()
            val buffer = ByteArray(bufferSize)
            val startTime = System.currentTimeMillis()
            val endTime = startTime + (durationSec * 1000L)

            while (System.currentTimeMillis() < endTime) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    pcmDataStream.write(buffer, 0, read)
                }
                delay(50)
            }
        } catch (e: Exception) {
            Log.e("AudioRecorderHelper", "Error during AudioRecord PCM read: ${e.message}")
        } finally {
            try { audioRecord.stop() } catch (_: Exception) {}
            try { audioRecord.release() } catch (_: Exception) {}
        }

        val pcmBytes = pcmDataStream.toByteArray()
        if (pcmBytes.isEmpty()) {
            Log.e("AudioRecorderHelper", "AudioRecord captured 0 bytes.")
            return null
        }

        // Convert PCM to WAV
        val wavBytes = pcmToWav(pcmBytes, chosenSampleRate, 1, 16)
        FileOutputStream(wavFile).use { it.write(wavBytes) }

        if (wavFile.exists() && wavFile.length() > 0) {
            val base64Wav = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
            return "data:audio/wav;base64,$base64Wav"
        }
        return null
    }

    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val totalAudioLen = pcmData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()

        val wavStream = ByteArrayOutputStream()
        wavStream.write(header)
        wavStream.write(pcmData)
        return wavStream.toByteArray()
    }

    fun cleanup() {
        try {
            if (outFile?.exists() == true) {
                outFile?.delete()
            }
        } catch (_: Exception) {}
    }
}


