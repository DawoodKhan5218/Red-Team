package com.example.data.sync

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.example.data.local.AppDatabase
import com.example.data.local.SmsLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmsSyncManager(private val context: Context) {

    private val db = AppDatabase.getInstance(context)

    suspend fun syncSmsMessages(): List<SmsLogEntity> = withContext(Dispatchers.IO) {
        val smsList = mutableListOf<SmsLogEntity>()
        val uri = Uri.parse("content://sms")

        try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE,
                    Telephony.Sms.THREAD_ID
                ),
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )

            cursor?.use { c ->
                val addressIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
                val typeIdx = c.getColumnIndex(Telephony.Sms.TYPE)
                val threadIdx = c.getColumnIndex(Telephony.Sms.THREAD_ID)

                while (c.moveToNext()) {
                    val address = if (addressIdx >= 0) c.getString(addressIdx) ?: "Unknown" else "Unknown"
                    val body = if (bodyIdx >= 0) c.getString(bodyIdx) ?: "" else ""
                    val date = if (dateIdx >= 0) c.getLong(dateIdx) else System.currentTimeMillis()
                    val typeInt = if (typeIdx >= 0) c.getInt(typeIdx) else 1
                    val threadId = if (threadIdx >= 0) c.getLong(threadIdx) else 0L

                    val typeStr = if (typeInt == Telephony.Sms.MESSAGE_TYPE_INBOX) "INCOMING" else "OUTGOING"

                    smsList.add(
                        SmsLogEntity(
                            messageBody = body,
                            address = address,
                            timestamp = date,
                            type = typeStr,
                            threadId = threadId,
                            isSynced = true
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Permission not granted or empty provider
        }

        db.smsDao().clearAll()
        if (smsList.isNotEmpty()) {
            db.smsDao().insertAllSms(smsList)
        }
        smsList
    }
}
