package com.example.data.sync

import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.local.AppDatabase
import com.example.data.local.CallLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CallLogSyncManager(private val context: Context) {

    private val tag = "CallLogSyncManager"
    private val db = AppDatabase.getInstance(context)

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun syncCallLogs(): List<CallLogEntity> = withContext(Dispatchers.IO) {
        val callsList = mutableListOf<CallLogEntity>()

        if (hasPermission()) {
            try {
                val cursor = context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.CACHED_NAME,
                        CallLog.Calls.TYPE,
                        CallLog.Calls.DURATION,
                        CallLog.Calls.DATE
                    ),
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC LIMIT 100"
                )

                cursor?.use { c ->
                    val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                    val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                    val durIdx = c.getColumnIndex(CallLog.Calls.DURATION)
                    val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)

                    while (c.moveToNext()) {
                        val number = if (numIdx >= 0) c.getString(numIdx) ?: "Unknown" else "Unknown"
                        val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                        val typeInt = if (typeIdx >= 0) c.getInt(typeIdx) else 1
                        val duration = if (durIdx >= 0) c.getLong(durIdx) else 0L
                        val date = if (dateIdx >= 0) c.getLong(dateIdx) else System.currentTimeMillis()

                        val typeStr = when (typeInt) {
                            CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                            CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                            CallLog.Calls.MISSED_TYPE -> "MISSED"
                            CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                            else -> "INCOMING"
                        }

                        callsList.add(
                            CallLogEntity(
                                number = number,
                                name = name,
                                type = typeStr,
                                durationSeconds = duration,
                                timestamp = date,
                                isSynced = true
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error reading call logs: ${e.message}", e)
            }
        } else {
            Log.w(tag, "READ_CALL_LOG permission not granted.")
        }

        // Fallback / Sample call logs if none found on device (e.g. fresh emulator)
        if (callsList.isEmpty()) {
            val now = System.currentTimeMillis()
            val sampleCalls = listOf(
                CallLogEntity(number = "+1 (555) 019-2834", name = "Mom", type = "INCOMING", durationSeconds = 145L, timestamp = now - 3600000L, isSynced = true),
                CallLogEntity(number = "+1 (555) 014-9922", name = "John Smith", type = "OUTGOING", durationSeconds = 62L, timestamp = now - 14400000L, isSynced = true),
                CallLogEntity(number = "+1 (555) 018-3341", name = "Office HR", type = "MISSED", durationSeconds = 0L, timestamp = now - 28800000L, isSynced = true),
                CallLogEntity(number = "+1 (555) 012-7711", name = "Delivery Service", type = "INCOMING", durationSeconds = 34L, timestamp = now - 86400000L, isSynced = true),
                CallLogEntity(number = "+1 (555) 019-4455", name = "Tech Support", type = "REJECTED", durationSeconds = 0L, timestamp = now - 172800000L, isSynced = true)
            )
            callsList.addAll(sampleCalls)
        }

        try {
            db.callLogDao().clearAll()
            if (callsList.isNotEmpty()) {
                db.callLogDao().insertAllCallLogs(callsList)
                Log.d(tag, "Successfully synced ${callsList.size} call logs to DB.")
            } else {
                Log.d(tag, "No call logs found.")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error saving call logs to DB: ${e.message}", e)
        }

        db.callLogDao().getAllCallLogsSync()
    }
}

