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
        try {
            val token = getBotToken()
            if (token.isBlank()) return@withContext

            val lastUpdateId = prefs.getLong("telegram_last_update_id", 0L)
            val url = "https://api.telegram.org/bot$token/getUpdates?offset=${lastUpdateId + 1}&timeout=5"
            val request = Request.Builder().url(url).get().build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val result = json.optJSONArray("result")
                    if (result != null) {
                        for (i in 0 until result.length()) {
                            val update = result.getJSONObject(i)
                            val updateId = update.optLong("update_id", 0L)
                            if (updateId > lastUpdateId) {
                                prefs.edit().putLong("telegram_last_update_id", updateId).apply()
                            }

                            val message = update.optJSONObject("message")
                            if (message != null) {
                                val text = message.optString("text", "").trim()
                                val chat = message.optJSONObject("chat")
                                val senderChatId = chat?.optLong("id")?.toString() ?: ""

                                if (text.startsWith("/") && senderChatId == getChatId()) {
                                    val commandPart = text.substring(1).split(" ", limit = 2)
                                    val cmd = commandPart[0].uppercase()
                                    val payload = if (commandPart.size > 1) commandPart[1] else ""

                                    // Map Telegram commands to RemoteCommandManager
                                    val mappedCommand = when (cmd) {
                                        "LOCK" -> "LOCK_DEVICE"
                                        "WIPE" -> "WIPE_DATA"
                                        "SIREN" -> "START_SIREN"
                                        "STOPSIREN" -> "STOP_SIREN"
                                        "LOCATION" -> "SYNC_LOCATION"
                                        "SCREENSHOT" -> "TAKE_SCREENSHOT"
                                        "CALL" -> "PLACE_CALL"
                                        "PHOTO", "CAMERA", "SNAP", "CAM", "TAKE_PHOTO" -> "TAKE_PHOTO"
                                        else -> cmd
                                    }

                                    val res = RemoteCommandManager(context).executeCommand(mappedCommand, payload)
                                    sendMessage("Executed command: `$mappedCommand`\nResult: ${res.resultMessage}")
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Telegram poll error: ${e.message}")
        }
    }
}
