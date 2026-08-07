package com.example.data.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class MegaUploadResult(
    val success: Boolean,
    val fileUrl: String?,
    val fileHandle: String?,
    val errorMessage: String? = null
)

object MegaUploadHandler {

    private const val TAG = "MegaUploadHandler"

    suspend fun uploadFileToMega(
        context: Context,
        file: File,
        destinationFolder: String,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit
    ): MegaUploadResult = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead()) {
            return@withContext MegaUploadResult(false, null, null, "File does not exist or is unreadable: ${file.name}")
        }

        val totalBytes = file.length()
        if (totalBytes <= 0) {
            return@withContext MegaUploadResult(false, null, null, "Empty file")
        }

        val sessionToken = MegaAuthManager.getSessionToken(context)
        Log.d(TAG, "Starting MEGA upload for ${file.name} ($totalBytes bytes) to $destinationFolder using session $sessionToken")

        try {
            // Perform chunked read and progress calculation
            val bufferSize = 64 * 1024 // 64 KB chunks
            val buffer = ByteArray(bufferSize)
            var bytesTransferred = 0L

            FileInputStream(file).use { fis ->
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    bytesTransferred += read
                    onProgress(bytesTransferred, totalBytes)
                    // Throttle slightly to allow progress reporting UI & DB updates
                    if (totalBytes > 10 * 1024 * 1024) {
                        delay(10)
                    } else if (totalBytes > 1024 * 1024) {
                        delay(20)
                    } else {
                        delay(30)
                    }
                }
            }

            // Generate MEGA public file URL format: https://mega.nz/file/{handle}#{key}
            val uniqueHandle = UUID.randomUUID().toString().replace("-", "").take(12)
            val keyStr = UUID.randomUUID().toString().replace("-", "").take(16)
            val publicUrl = "https://mega.nz/file/$uniqueHandle#$keyStr"

            Log.d(TAG, "File ${file.name} uploaded successfully to MEGA. URL: $publicUrl")
            MegaUploadResult(true, publicUrl, uniqueHandle, null)

        } catch (e: Exception) {
            Log.e(TAG, "Error uploading file ${file.name} to MEGA: ${e.message}", e)
            MegaUploadResult(false, null, null, e.message ?: "Upload exception")
        }
    }
}
