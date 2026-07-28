package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.sync.RemoteCommandManager

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            RemoteCommandManager(applicationContext).executeCommand("SYNC_ALL", "WorkManager Periodic 15min Worker")
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
