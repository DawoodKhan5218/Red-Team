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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

class AudioRecorderHelper(private val context: Context) {
    private var lastRecordedFile: File? = null

    suspend fun startRecordingToFile(durationSec: Int = 1800): File? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("AudioRecorderHelper", "RECORD_AUDIO permission is not granted.")
            throw SecurityException("RECORD_AUDIO permission not granted. Please grant microphone permission in the app.")
        }

        val finalDuration = if (durationSec <= 0) 10 else durationSec.coerceAtLeast(2)

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

        var resultFile: File? = null

        // Strategy 1: Direct AudioRecord streaming to disk as WAV
        try {
            resultFile = tryAudioRecordStream(finalDuration)
        } catch (e: Exception) {
            Log.w("AudioRecorderHelper", "AudioRecord stream failed: ${e.message}, trying MediaRecorder fallback")
        }

        // Strategy 2: MediaRecorder fallback
        if (resultFile == null || !resultFile.exists() || resultFile.length() <= 44) {
            try {
                resultFile = tryMediaRecorder(finalDuration)
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

        if (resultFile != null && resultFile.exists() && resultFile.length() > 44) {
            lastRecordedFile = resultFile
            return@withContext resultFile
        } else {
            throw Exception("Audio recording failed across all available audio engines.")
        }
    }

    suspend fun startRecording(durationSec: Int = 1800): String? = withContext(Dispatchers.IO) {
        val file = startRecordingToFile(durationSec) ?: return@withContext null
        val bytes = file.readBytes()
        val base64Wav = Base64.encodeToString(bytes, Base64.NO_WRAP)
        "data:audio/wav;base64,$base64Wav"
    }

    @SuppressLint("MissingPermission")
    private suspend fun tryAudioRecordStream(durationSec: Int): File? {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = if (minBufferSize > 0) (minBufferSize * 4).coerceAtLeast(8192) else 8192

        // Prioritized sources: MIC is standard and triggers privacy green dot
        val sources = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.UNPROCESSED
        )

        var audioRecord: AudioRecord? = null
        for (source in sources) {
            try {
                val record = AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize)
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    record.startRecording()
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord = record
                        Log.d("AudioRecorderHelper", "Successfully started AudioRecord with source=$source (RECORDSTATE_RECORDING)")
                        break
                    } else {
                        record.release()
                    }
                } else {
                    record.release()
                }
            } catch (e: Exception) {
                Log.w("AudioRecorderHelper", "Failed to init source=$source: ${e.message}")
            }
        }

        if (audioRecord == null) {
            Log.e("AudioRecorderHelper", "No AudioRecord instance could be initialized in RECORDING state.")
            return null
        }

        val targetFile = File(context.cacheDir, "remote_audio_${System.currentTimeMillis()}.wav")
        if (targetFile.exists()) targetFile.delete()

        var totalPcmBytes = 0L
        val fos = FileOutputStream(targetFile)

        try {
            // Write placeholder 44-byte WAV header
            val placeholderHeader = ByteArray(44)
            fos.write(placeholderHeader)

            val startTime = System.currentTimeMillis()
            val endTime = startTime + (durationSec * 1000L)
            val readBuffer = ByteArray(bufferSize)

            while (System.currentTimeMillis() < endTime && kotlinx.coroutines.currentCoroutineContext().isActive) {
                val read = audioRecord.read(readBuffer, 0, readBuffer.size)
                if (read > 0) {
                    fos.write(readBuffer, 0, read)
                    totalPcmBytes += read
                } else if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                    Log.w("AudioRecorderHelper", "AudioRecord read error code: $read")
                    kotlinx.coroutines.delay(20)
                } else {
                    kotlinx.coroutines.delay(5)
                }
            }
            fos.flush()
        } finally {
            try { fos.close() } catch (_: Exception) {}
            try { audioRecord.stop() } catch (_: Exception) {}
            try { audioRecord.release() } catch (_: Exception) {}
        }

        if (totalPcmBytes <= 0) {
            Log.e("AudioRecorderHelper", "AudioRecord streamed 0 PCM bytes.")
            try { targetFile.delete() } catch (_: Exception) {}
            return null
        }

        // Seek to beginning and write complete, valid WAV header
        try {
            RandomAccessFile(targetFile, "rw").use { raf ->
                raf.seek(0)
                val wavHeader = createWavHeader(totalPcmBytes, sampleRate, 1, 16)
                raf.write(wavHeader)
            }
            Log.d("AudioRecorderHelper", "Successfully recorded WAV file: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            return targetFile
        } catch (e: Exception) {
            Log.e("AudioRecorderHelper", "Failed to write WAV header: ${e.message}")
            return null
        }
    }

    private suspend fun tryMediaRecorder(durationSec: Int): File? {
        val targetFile = File(context.cacheDir, "remote_audio_media_${System.currentTimeMillis()}.wav")
        if (targetFile.exists()) {
            targetFile.delete()
        }

        val validSources = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        )

        var recorder: MediaRecorder? = null
        var isStarted = false
        var lastException: Exception? = null

        for (source in validSources) {
            try {
                val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }

                rec.setAudioSource(source)
                rec.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                rec.setOutputFile(targetFile.absolutePath)

                rec.prepare()
                rec.start()
                recorder = rec
                isStarted = true
                Log.d("AudioRecorderHelper", "MediaRecorder started with source=$source")
                break
            } catch (e: Exception) {
                lastException = e
                try { recorder?.release() } catch (_: Exception) {}
                recorder = null
            }
        }

        if (!isStarted || recorder == null) {
            throw lastException ?: Exception("MediaRecorder failed to start")
        }

        try {
            kotlinx.coroutines.delay(durationSec * 1000L)
        } catch (_: Exception) {
        } finally {
            try { recorder.stop() } catch (_: Throwable) {}
            try { recorder.reset() } catch (_: Throwable) {}
            try { recorder.release() } catch (_: Throwable) {}
        }

        if (targetFile.exists() && targetFile.length() > 100) {
            return targetFile
        }
        return null
    }

    private fun createWavHeader(totalAudioLen: Long, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
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
        header[20] = 1 // PCM format
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

        return header
    }

    fun cleanup() {
        try {
            lastRecordedFile?.let { if (it.exists()) it.delete() }
        } catch (_: Exception) {}
    }
}
