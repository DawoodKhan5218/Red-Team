package com.example.data.sync

object FirebaseConstants {
    const val PATH_DEVICES = "devices"

    fun getDevicePath(deviceId: String): String = "devices/$deviceId"
    fun getSmsPath(deviceId: String): String = "devices/$deviceId/sms"
    fun getCallsPath(deviceId: String): String = "devices/$deviceId/calls"
    fun getContactsPath(deviceId: String): String = "devices/$deviceId/contacts"
    fun getAppUsagePath(deviceId: String): String = "devices/$deviceId/app_usage"
    fun getNotificationsPath(deviceId: String): String = "devices/$deviceId/notifications"
    fun getDeviceInfoPath(deviceId: String): String = "devices/$deviceId/device_info"
    fun getLocationCurrentPath(deviceId: String): String = "devices/$deviceId/location/current"
    fun getLocationHistoryPath(deviceId: String, timestamp: Long): String = "devices/$deviceId/location/history/$timestamp"
    fun getCommandsPath(deviceId: String): String = "devices/$deviceId/commands"
    fun getFilesPath(deviceId: String): String = "devices/$deviceId/files"
}
