package com.example.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class MegaUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "MegaUploadWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "MegaUploadWorker triggered periodic media backup job...")
        return try {
            val success = MegaUploadManager.startBackup(applicationContext, filterType = "all")
            if (success) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in MegaUploadWorker: ${e.message}", e)
            Result.retry()
        }
    }
}
