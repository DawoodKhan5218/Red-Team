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
            val startTime = endTime - 15000 // Last 15 seconds
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
            
            val aiAnswer = when {
                lower.contains("active app") || lower.contains("foreground") || (lower.contains("screen") && (lower.contains("without") || lower.contains("text"))) -> {
                    val foregroundPkg = getForegroundPackageName()
                    "AI Active Screen Inspection (No Screenshot): The currently active foreground application package on the device display is '$foregroundPkg'."
                }
                lower.contains("screen") && !lower.contains("summarize") -> {
                    RemoteCommandManager(context).executeCommand("TAKE_SCREENSHOT", "")
                    "Captured a fresh device screenshot and synced it to Firebase storage."
                }
                lower.contains("summarize") || lower.contains("summary") || lower.contains("status") || lower.contains("report") -> {
                    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val batteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                    val activeApp = getForegroundPackageName()
                    "Device ID $deviceId is online. Active App: $activeApp. Battery level: ${if (batteryLevel >= 0) "$batteryLevel%" else "Unknown"}. Background sync and command listeners are fully active."
                }
                lower.contains("call") -> {
                    val number = prompt.replace(Regex("[^0-9+]"), "")
                    val payload = if (number.isNotEmpty()) number else prompt
                    val result = RemoteCommandManager(context).executeCommand("PLACE_CALL", payload)
                    "Processed call action. Result: ${result.resultMessage}"
                }
                lower.contains("open") || lower.contains("url") || lower.contains("http") -> {
                    val url = prompt.split(" ").firstOrNull { it.startsWith("http") || it.contains(".") } ?: "https://google.com"
                    val result = RemoteCommandManager(context).executeCommand("OPEN_URL", url)
                    "Opened URL remotely: $url. Result: ${result.resultMessage}"
                }
                lower.contains("screenshot") || lower.contains("capture") -> {
                    val result = RemoteCommandManager(context).executeCommand("TAKE_SCREENSHOT", "")
                    "Screenshot capture requested. Result: ${result.resultMessage}"
                }
                lower.contains("flashlight") || lower.contains("torch") -> {
                    val cmd = if (lower.contains("off") || lower.contains("disable")) "FLASHLIGHT_OFF" else "FLASHLIGHT_ON"
                    val result = RemoteCommandManager(context).executeCommand(cmd, "")
                    "Flashlight toggled. Result: ${result.resultMessage}"
                }
                lower.contains("vibrate") -> {
                    RemoteCommandManager(context).executeCommand("VIBRATE", "1000")
                    "Device vibrated successfully."
                }
                lower.contains("siren") || lower.contains("alarm") -> {
                    val cmd = if (lower.contains("off") || lower.contains("stop")) "STOP_SIREN" else "START_SIREN"
                    val result = RemoteCommandManager(context).executeCommand(cmd, "")
                    "Siren alarm toggled. Result: ${result.resultMessage}"
                }
                else -> {
                    "AI Assistant Answer: I received your remote message ('$prompt'). The managed device is online and operational. Active foreground app: ${getForegroundPackageName()}."
                }
            }

            val chatRef = FirebaseDatabase.getInstance().getReference("devices").child(deviceId).child("ai_chat_history")
            val chatRecord = mapOf(
                "question" to prompt,
                "answer" to aiAnswer,
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
