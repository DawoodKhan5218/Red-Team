package com.example.data.sync

import android.content.Context
import com.example.data.local.CallLogEntity

class CallLogsCollector(private val context: Context) {
    private val syncManager = CallLogSyncManager(context)

    suspend fun collectCallLogs(): List<CallLogEntity> {
        return syncManager.syncCallLogs()
    }
}
