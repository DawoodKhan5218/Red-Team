package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsDao {
    @Query("SELECT * FROM sms_logs ORDER BY timestamp DESC")
    fun getAllSms(): Flow<List<SmsLogEntity>>

    @Query("SELECT * FROM sms_logs ORDER BY timestamp DESC")
    suspend fun getAllSmsSync(): List<SmsLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSms(sms: SmsLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllSms(smsList: List<SmsLogEntity>)

    @Query("DELETE FROM sms_logs")
    suspend fun clearAll()
}

@Dao
interface CallLogDao {
    @Query("SELECT * FROM call_logs ORDER BY timestamp DESC")
    fun getAllCallLogs(): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_logs ORDER BY timestamp DESC")
    suspend fun getAllCallLogsSync(): List<CallLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallLog(call: CallLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllCallLogs(calls: List<CallLogEntity>)

    @Query("DELETE FROM call_logs")
    suspend fun clearAll()
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    suspend fun getAllContactsSync(): List<ContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllContacts(contacts: List<ContactEntity>)

    @Query("DELETE FROM contacts")
    suspend fun clearAll()
}

@Dao
interface LocationDao {
    @Query("SELECT * FROM location_logs ORDER BY timestamp DESC")
    fun getAllLocations(): Flow<List<LocationLogEntity>>

    @Query("SELECT * FROM location_logs ORDER BY timestamp DESC")
    suspend fun getAllLocationsSync(): List<LocationLogEntity>

    @Query("SELECT * FROM location_logs ORDER BY timestamp DESC LIMIT 1")
    fun getLatestLocation(): Flow<LocationLogEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(location: LocationLogEntity)

    @Query("DELETE FROM location_logs")
    suspend fun clearAll()
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notification_logs ORDER BY timestamp DESC")
    fun getAllNotifications(): Flow<List<NotificationLogEntity>>

    @Query("SELECT * FROM notification_logs ORDER BY timestamp DESC")
    suspend fun getAllNotificationsSync(): List<NotificationLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: NotificationLogEntity)

    @Query("DELETE FROM notification_logs")
    suspend fun clearAll()
}

@Dao
interface AppUsageDao {
    @Query("SELECT * FROM app_usage_logs ORDER BY totalTimeInForegroundMs DESC")
    fun getAllAppUsage(): Flow<List<AppUsageEntity>>

    @Query("SELECT * FROM app_usage_logs ORDER BY totalTimeInForegroundMs DESC")
    suspend fun getAllAppUsageSync(): List<AppUsageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllUsage(usageList: List<AppUsageEntity>)

    @Query("DELETE FROM app_usage_logs")
    suspend fun clearAll()
}

@Dao
interface DeviceInfoDao {
    @Query("SELECT * FROM device_info WHERE id = 1")
    fun getDeviceInfo(): Flow<DeviceInfoEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDeviceInfo(info: DeviceInfoEntity)
}

@Dao
interface RemoteCommandDao {
    @Query("SELECT * FROM remote_commands ORDER BY createdAt DESC")
    fun getAllCommands(): Flow<List<RemoteCommandEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommand(cmd: RemoteCommandEntity)

    @Query("UPDATE remote_commands SET status = :status, executedAt = :executedAt, resultMessage = :result WHERE id = :id")
    suspend fun updateCommandStatus(id: String, status: String, executedAt: Long, result: String)
}
