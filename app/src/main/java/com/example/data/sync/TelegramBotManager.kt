package com.example.data.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class TelegramBotManager(private val context: Context) {
    private val TAG = "TelegramBotManager"
    private val client = OkHttpClient()
    private val prefs = context.getSharedPreferences("admin_sync_prefs", Context.MODE_PRIVATE)

    private fun getBotToken(): String {
        return prefs.getString("telegram_bot_token", "") ?: ""
    }

    private fun getChatId(): String {
        return prefs.getString("telegram_chat_id", "") ?: ""
    }

    suspend fun saveConfig(token: String, chatId: String) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString("telegram_bot_token", token.trim())
                .putString("telegram_chat_id", chatId.trim()).apply()
        }
    }

    suspend fun sendMessage(message: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = getBotToken()
            val chatId = getChatId()
            if (token.isBlank() || chatId.isBlank()) return@withContext false

            val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
            val formattedMessage = "🤖 *Enterprise Device [${deviceId.take(6)}]*\n\n$message"

            val url = "https://api.telegram.org/bot$token/sendMessage"
            val json = JSONObject().apply {
                put("chat_id", chatId)
                put("text", formattedMessage)
                put("parse_mode", "Markdown")
            }
            val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(requestBody).build()

            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Telegram send message error: ${e.message}")
            false
        }
    }

    suspend fun pollTelegramCommands(): Unit = withContext(Dispatchers.IO) {
        // Telegram bot control removed. Telegram is now used solely for sending alerts and captured media.
    }
}
