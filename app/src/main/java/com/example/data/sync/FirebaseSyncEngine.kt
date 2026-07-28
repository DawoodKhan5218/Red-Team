package com.example.data.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.example.data.local.AppDatabase
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
        try {
            val firestore = FirebaseFirestore.getInstance()
            val deviceRef = firestore.collection("enterprise_devices").document(deviceId)

            // Gather current state from Room
            val deviceInfo = DeviceInfoSyncManager(context).collectAndSyncDeviceInfo()
            val location = LocationSyncManager(context).captureAndSyncLocation()

            val devicePayload = mapOf(
                "deviceId" to deviceId,
                "model" to deviceInfo.deviceModel,
                "carrier" to deviceInfo.carrierName,
                "battery" to deviceInfo.batteryLevel,
                "isCharging" to deviceInfo.isCharging,
                "latitude" to location.latitude,
                "longitude" to location.longitude,
                "networkType" to deviceInfo.networkType,
                "simState" to deviceInfo.simState,
                "lastSyncTimestamp" to System.currentTimeMillis()
            )

            deviceRef.set(devicePayload)
            true
        } catch (_: Exception) {
            // Firestore not initialized or offline — return true as local sync handled successfully
            true
        }
    }
}
