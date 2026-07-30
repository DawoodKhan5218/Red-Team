package com.example.data

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore

class FirebaseStorageHelper(private val context: Context) {
    private val tag = "FirebaseStorageHelper"

    fun saveDeviceInfo(info: Map<String, Any>) {
        try {
            val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"

            // Save to Firebase Realtime Database
            val rtdb = FirebaseDatabase.getInstance()
            rtdb.getReference("devices/$deviceId/device_info").setValue(info)

            // Save to Firestore
            val firestore = FirebaseFirestore.getInstance()
            firestore.collection("enterprise_devices").document(deviceId).set(info)

            Log.d(tag, "Saved device info to Firebase for device $deviceId")
        } catch (e: Exception) {
            Log.e(tag, "Failed to save device info to Firebase: ${e.message}", e)
        }
    }
}
