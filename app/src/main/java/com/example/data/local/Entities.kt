package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sms_logs")
data class SmsLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageBody: String,
    val address: String,
    val timestamp: Long,
    val type: String, // "INCOMING", "OUTGOING"
    val threadId: Long = 0,
    val isSynced: Boolean = false
)

@Entity(tableName = "call_logs")
data class CallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val name: String,
    val type: String, // "INCOMING", "OUTGOING", "MISSED", "REJECTED"
    val durationSeconds: Long,
    val timestamp: Long,
    val isSynced: Boolean = false
)

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactId: String,
    val displayName: String,
    val primaryPhone: String,
    val secondaryPhone: String = "",
    val email: String = "",
    val lastModified: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false
)

@Entity(tableName = "location_logs")
data class LocationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val speed: Float = 0f,
    val bearing: Float = 0f,
    val accuracy: Float = 0f,
    val provider: String = "GPS",
    val timestamp: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false
)

@Entity(tableName = "notification_logs")
data class NotificationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false
)

@Entity(tableName = "app_usage_logs")
data class AppUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val totalTimeInForegroundMs: Long,
    val lastTimeUsed: Long,
    val launchCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false
)

@Entity(tableName = "device_info")
data class DeviceInfoEntity(
    @PrimaryKey val id: Int = 1,
    val simState: String,
    val carrierName: String,
    val countryIso: String,
    val mccMnc: String,
    val iccid: String,
    val subscriptionId: Int,
    val phoneNumber: String,
    val phoneType: String,
    val networkType: String,
    val isRoaming: Boolean,
    val dataState: String,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val chargeType: String,
    val totalStorageMb: Long,
    val freeStorageMb: Long,
    val wifiSsid: String,
    val wifiBssid: String,
    val wifiRssi: Int,
    val localIp: String,
    val macAddress: String,
    val deviceModel: String,
    val androidVersion: String,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "remote_commands")
data class RemoteCommandEntity(
    @PrimaryKey val id: String, // UUID
    val commandType: String, // SYNC_ALL, SYNC_LOCATION, GLOBAL_BACK, GLOBAL_HOME, GLOBAL_RECENTS, LOCK_SCREEN, PLAY_ALARM, MUTE_AUDIO, UNMUTE_AUDIO
    val payload: String = "",
    val status: String, // PENDING, EXECUTING, COMPLETED, FAILED
    val createdAt: Long = System.currentTimeMillis(),
    val executedAt: Long = 0,
    val resultMessage: String = ""
)
