package com.example.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.data.sync.FirebaseSyncEngine
import com.example.data.sync.NotificationSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkgName = sbn.packageName ?: "unknown"
        val extras = sbn.notification?.extras
        val title = extras?.getCharSequence("android.title")?.toString() ?: ""
        val text = extras?.getCharSequence("android.text")?.toString() ?: ""

        if (title.isNotEmpty() || text.isNotEmpty()) {
            serviceScope.launch {
                try {
                    val appContext = applicationContext
                    NotificationSyncManager(appContext).saveCapturedNotification(pkgName, title, text)
                    val engine = FirebaseSyncEngine(appContext)
                    engine.uploadSingleNotificationInstant(pkgName, title, text, System.currentTimeMillis())
                    engine.pushTelemetryToFirebase()
                } catch (_: Exception) {}
            }
        }
    }
}

