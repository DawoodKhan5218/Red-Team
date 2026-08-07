package com.example.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object UploadScheduler {

    private const val TAG = "UploadScheduler"
    private const val PERIODIC_WORK_NAME = "MegaMediaPeriodicBackupWork"

    fun schedulePeriodicUploads(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicWorkRequest = PeriodicWorkRequestBuilder<MegaUploadWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWorkRequest
            )

            Log.d(TAG, "Periodic 6-hour media upload scheduled successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule periodic uploads: ${e.message}", e)
        }
    }

    fun cancelPeriodicUploads(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
            Log.d(TAG, "Periodic media upload cancelled.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel periodic uploads: ${e.message}", e)
        }
    }
}
