package com.example.data.sync

import android.app.usage.UsageStatsManager
import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.AppUsageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppUsageSyncManager(private val context: Context) {

    private val db = AppDatabase.getInstance(context)

    suspend fun syncAppUsageStats(): List<AppUsageEntity> = withContext(Dispatchers.IO) {
        val usageList = mutableListOf<AppUsageEntity>()
        val pm = context.packageManager
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

        val endTime = System.currentTimeMillis()
        val startTime = endTime - (1000 * 60 * 60 * 24) // Past 24 hours

        try {
            val stats = usageStatsManager?.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            if (!stats.isNullOrEmpty()) {
                val filteredStats = stats.filter { it.totalTimeInForeground > 1000 }
                    .sortedByDescending { it.totalTimeInForeground }
                    .take(30)

                for (s in filteredStats) {
                    val appName = try {
                        val appInfo = pm.getApplicationInfo(s.packageName, 0)
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (_: Exception) {
                        s.packageName
                    }

                    usageList.add(
                        AppUsageEntity(
                            packageName = s.packageName,
                            appName = appName,
                            totalTimeInForegroundMs = s.totalTimeInForeground,
                            lastTimeUsed = s.lastTimeUsed,
                            launchCount = (s.totalTimeInForeground / (60 * 1000)).toInt().coerceAtLeast(1),
                            timestamp = System.currentTimeMillis(),
                            isSynced = true
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // UsageStats access permission not granted
        }

        db.appUsageDao().clearAll()
        if (usageList.isNotEmpty()) {
            db.appUsageDao().insertAllUsage(usageList)
        }
        usageList
    }
}
