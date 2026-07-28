package com.example.data.sync

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.NotificationLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NotificationSyncManager(private val context: Context) {

    private val db = AppDatabase.getInstance(context)

    suspend fun saveCapturedNotification(packageName: String, title: String, text: String) = withContext(Dispatchers.IO) {
        val entity = NotificationLogEntity(
            packageName = packageName,
            title = title.ifEmpty { "System Notification" },
            text = text,
            timestamp = System.currentTimeMillis(),
            isSynced = true
        )
        db.notificationDao().insertNotification(entity)
    }
}
