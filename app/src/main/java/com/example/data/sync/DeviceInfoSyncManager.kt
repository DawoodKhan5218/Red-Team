package com.example.data.sync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.FirebaseStorageHelper
import com.example.data.local.AppDatabase
import com.example.data.local.DeviceInfoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles gathering phone specifications, battery status, and SIM card details
 * (operator, operator name, country ISO, serial number, phone number, network type)
 * and uploading them to Firebase online storage.
 * Storage space is measured in GBs.
 */
class DeviceInfoSyncManager(
    private val context: Context,
    private val firebaseStorageHelper: FirebaseStorageHelper = FirebaseStorageHelper(context)
) {
    private val tag = "DeviceInfoSyncManager"
    private val db = AppDatabase.getInstance(context)

    /**
     * Reads current device specifications, battery information, SIM card info, and Wi-Fi status.
     * Storage is measured in GBs.
     */
    suspend fun syncDeviceInfo(): Result<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            // Get battery status intent
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                context.registerReceiver(null, filter)
            }

            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct: Float = if (level >= 0 && scale > 0) (level / scale.toFloat()) * 100 else -1f

            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val chargePlug: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val plugType = when (chargePlug) {
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_AC -> "AC Charger"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> "Not Plugged"
            }

            // Calculate free & total storage space in GBs
            val stat = StatFs(Environment.getDataDirectory().path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val freeStorageGb = String.format(Locale.US, "%.2f", availableBytes / (1024.0 * 1024.0 * 1024.0)).toDouble()
            val totalStorageGb = String.format(Locale.US, "%.2f", totalBytes / (1024.0 * 1024.0 * 1024.0)).toDouble()

            // Gather SIM & Telephony details, Wi-Fi status, and Public IP
            val simInfoMap = getSimAndNetworkInfo()
            val wifiInfoMap = getWifiStatusInfo()
            val publicIp = fetchPublicIpAddress()

                val nowMs = System.currentTimeMillis()
                val formattedTime = try {
                    SimpleDateFormat("MMM dd, yyyy, hh:mm:ss a", Locale.US).format(Date(nowMs))
                } catch (_: Exception) { "" }

                val info = mutableMapOf<String, Any>(
                    "model" to Build.MODEL,
                    "manufacturer" to Build.MANUFACTURER,
                    "device" to Build.DEVICE,
                    "product" to Build.PRODUCT,
                    "androidVersion" to Build.VERSION.RELEASE,
                    "sdkLevel" to Build.VERSION.SDK_INT,
                    "batteryLevel" to batteryPct.toInt(),
                    "isCharging" to isCharging,
                    "powerSource" to plugType,
                    "freeStorageGb" to freeStorageGb,
                    "totalStorageGb" to totalStorageGb,
                    "freeStorageMb" to (availableBytes / (1024 * 1024)),
                    "totalStorageMb" to (totalBytes / (1024 * 1024)),
                    "publicIp" to publicIp,
                    "publicIpAddress" to publicIp,
                    "updatedAt" to nowMs,
                    "formattedLastSync" to formattedTime,
                    "formattedUpdatedAt" to formattedTime,
                    "formattedDate" to formattedTime,
                    "formattedTime" to formattedTime
                )
            info.putAll(simInfoMap)
            info.putAll(wifiInfoMap)

            firebaseStorageHelper.saveDeviceInfo(info)
            Log.d(tag, "Successfully backed up phone info, SIM details, and battery level: ${info["model"]}, ${info["simOperatorName"]}, storage: $freeStorageGb GB / $totalStorageGb GB")
            Result.success(info)
        } catch (e: Exception) {
            Log.e(tag, "Failed to sync device info: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Backward-compatible helper method that syncs device info and updates Room local database.
     */
    @SuppressLint("MissingPermission")
    suspend fun collectAndSyncDeviceInfo(): DeviceInfoEntity = withContext(Dispatchers.IO) {
        val syncResult = syncDeviceInfo()
        val infoMap = syncResult.getOrDefault(emptyMap())

        val freeGb = (infoMap["freeStorageGb"] as? Double) ?: 0.0
        val totalGb = (infoMap["totalStorageGb"] as? Double) ?: 0.0
        val freeMb = (infoMap["freeStorageMb"] as? Long) ?: (freeGb * 1024).toLong()
        val totalMb = (infoMap["totalStorageMb"] as? Long) ?: (totalGb * 1024).toLong()

        val deviceInfoEntity = DeviceInfoEntity(
            id = 1,
            simState = (infoMap["simState"] as? String) ?: "Unknown",
            carrierName = (infoMap["simOperatorName"] as? String) ?: "N/A",
            countryIso = (infoMap["simCountryIso"] as? String) ?: "N/A",
            mccMnc = (infoMap["simOperator"] as? String) ?: "N/A",
            iccid = (infoMap["simSerialNumber"] as? String) ?: "N/A",
            subscriptionId = 1,
            phoneNumber = (infoMap["phoneNumber"] as? String) ?: "N/A",
            phoneType = (infoMap["networkType"] as? String) ?: "Unknown",
            networkType = (infoMap["networkType"] as? String) ?: "Unknown",
            isRoaming = (infoMap["isNetworkRoaming"] as? Boolean) ?: false,
            dataState = if ((infoMap["isWifiConnected"] as? Boolean) == true) "CONNECTED" else "DISCONNECTED",
            batteryLevel = (infoMap["batteryLevel"] as? Int) ?: 0,
            isCharging = (infoMap["isCharging"] as? Boolean) ?: false,
            chargeType = (infoMap["powerSource"] as? String) ?: "Not Plugged",
            totalStorageMb = totalMb,
            freeStorageMb = freeMb,
            wifiSsid = (infoMap["wifiSsid"] as? String) ?: "Not Connected",
            wifiBssid = (infoMap["wifiBssid"] as? String) ?: "N/A",
            wifiRssi = (infoMap["wifiRssi"] as? Int) ?: 0,
            localIp = (infoMap["wifiIpAddress"] as? String) ?: (infoMap["localIp"] as? String) ?: "N/A",
            publicIp = (infoMap["publicIp"] as? String) ?: "N/A",
            macAddress = "Restricted by OS",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            lastUpdated = System.currentTimeMillis()
        )

        try {
            db.deviceInfoDao().saveDeviceInfo(deviceInfoEntity)
        } catch (e: Exception) {
            Log.e(tag, "Error saving DeviceInfoEntity to database: ${e.message}")
        }

        deviceInfoEntity
    }

    /**
     * Collects detailed SIM card information, multi-SIM slot details, and network status.
     */
    private fun getSimAndNetworkInfo(): Map<String, Any> {
        var simOperator = ""
        var simOperatorName = ""
        var simCountryIso = ""
        var simSerialNumber = ""
        var phoneNumber = ""
        var networkTypeStr = "Unknown"

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager

        val hasReadPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val hasReadPhoneNumbers = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_NUMBERS
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED || hasReadPhoneState

        val simSlots = mutableListOf<Map<String, Any>>()
        var activeSimCount = 0
        var primarySubInfo: SubscriptionInfo? = null
        var primaryTelephonyManager: TelephonyManager? = telephonyManager

        val defaultDataSubId = try {
            if (subscriptionManager != null) SubscriptionManager.getDefaultDataSubscriptionId() else SubscriptionManager.INVALID_SUBSCRIPTION_ID
        } catch (e: Exception) { SubscriptionManager.INVALID_SUBSCRIPTION_ID }

        val defaultSmsSubId = try {
            if (subscriptionManager != null) SubscriptionManager.getDefaultSmsSubscriptionId() else SubscriptionManager.INVALID_SUBSCRIPTION_ID
        } catch (e: Exception) { SubscriptionManager.INVALID_SUBSCRIPTION_ID }

        val defaultVoiceSubId = try {
            if (subscriptionManager != null) SubscriptionManager.getDefaultVoiceSubscriptionId() else SubscriptionManager.INVALID_SUBSCRIPTION_ID
        } catch (e: Exception) { SubscriptionManager.INVALID_SUBSCRIPTION_ID }

        if (hasReadPhoneState && subscriptionManager != null) {
            try {
                @Suppress("MissingPermission")
                val activeSubscriptions: List<SubscriptionInfo>? = subscriptionManager.activeSubscriptionInfoList
                if (!activeSubscriptions.isNullOrEmpty()) {
                    activeSimCount = activeSubscriptions.size

                    val targetSubId = if (defaultDataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) defaultDataSubId
                    else SubscriptionManager.getDefaultSubscriptionId()

                    primarySubInfo = activeSubscriptions.firstOrNull { it.subscriptionId == targetSubId }
                        ?: activeSubscriptions.first()

                    if (telephonyManager != null && primarySubInfo != null) {
                        primaryTelephonyManager = telephonyManager.createForSubscriptionId(primarySubInfo.subscriptionId)
                    }

                    // Build list of all active SIM slots
                    for (sub in activeSubscriptions) {
                        val mcc = sub.mccString ?: sub.mcc.let { if (it != 0 && it != 2147483647) it.toString() else "" }
                        val mnc = sub.mncString ?: sub.mnc.let { if (it != 0 && it != 2147483647) it.toString() else "" }
                        val opCode = if (mcc.isNotEmpty() && mnc.isNotEmpty()) "$mcc$mnc" else ""

                        val num = if (hasReadPhoneNumbers) {
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    subscriptionManager.getPhoneNumber(sub.subscriptionId)
                                } else {
                                    @Suppress("DEPRECATION")
                                    sub.number ?: ""
                                }
                            } catch (e: Exception) { "" }
                        } else ""

                        val iccId = if (hasReadPhoneState) {
                            try { sub.iccId ?: "" } catch (e: Exception) { "" }
                        } else ""

                        val isEmbedded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            try { sub.isEmbedded } catch (e: Exception) { false }
                        } else false

                        simSlots.add(
                            mapOf(
                                "slotIndex" to sub.simSlotIndex,
                                "subscriptionId" to sub.subscriptionId,
                                "carrierName" to (sub.carrierName?.toString() ?: ""),
                                "displayName" to (sub.displayName?.toString() ?: ""),
                                "countryIso" to (sub.countryIso ?: ""),
                                "mcc" to mcc,
                                "mnc" to mnc,
                                "operatorCode" to opCode,
                                "phoneNumber" to num.ifBlank { "N/A" },
                                "iccId" to iccId.ifBlank { "N/A" },
                                "isEmbedded" to isEmbedded,
                                "isDefaultData" to (sub.subscriptionId == defaultDataSubId),
                                "isDefaultSms" to (sub.subscriptionId == defaultSmsSubId),
                                "isDefaultVoice" to (sub.subscriptionId == defaultVoiceSubId)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Error accessing SubscriptionManager active subscriptions: ${e.message}")
            }
        }

        // Extract values from primary SubscriptionInfo if available
        if (primarySubInfo != null) {
            simOperatorName = primarySubInfo.carrierName?.toString()
                ?.ifBlank { primarySubInfo.displayName?.toString() } ?: ""
            simCountryIso = primarySubInfo.countryIso ?: ""

            simOperator = try {
                val mcc = primarySubInfo.mccString ?: primarySubInfo.mcc.toString()
                val mnc = primarySubInfo.mncString ?: primarySubInfo.mnc.toString()
                if (mcc.isNotEmpty() && mnc.isNotEmpty() && mcc != "0" && mcc != "2147483647") "$mcc$mnc" else ""
            } catch (e: Exception) {
                ""
            }

            if (hasReadPhoneState) {
                simSerialNumber = try { primarySubInfo.iccId ?: "" } catch (e: Exception) { "" }
            }

            if (hasReadPhoneNumbers) {
                phoneNumber = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && subscriptionManager != null) {
                        subscriptionManager.getPhoneNumber(primarySubInfo.subscriptionId)
                    } else {
                        @Suppress("DEPRECATION")
                        primarySubInfo.number ?: ""
                    }
                } catch (e: Exception) {
                    ""
                }
            }
        }

        // Fallbacks using TelephonyManager
        val tm = primaryTelephonyManager ?: telephonyManager
        var networkOperatorCode = ""
        var networkOperatorName = ""
        var isNetworkRoaming = false

        if (tm != null) {
            if (simOperator.isBlank()) {
                simOperator = try {
                    tm.simOperator?.ifBlank { tm.networkOperator } ?: ""
                } catch (e: Exception) { "" }
            }
            if (simOperatorName.isBlank()) {
                simOperatorName = try {
                    tm.simOperatorName?.ifBlank { tm.networkOperatorName } ?: ""
                } catch (e: Exception) { "" }
            }
            if (simCountryIso.isBlank()) {
                simCountryIso = try {
                    tm.simCountryIso?.ifBlank { tm.networkCountryIso } ?: ""
                } catch (e: Exception) { "" }
            }
            if (simSerialNumber.isBlank() && hasReadPhoneState) {
                simSerialNumber = try {
                    @Suppress("DEPRECATION", "MissingPermission")
                    tm.simSerialNumber ?: ""
                } catch (e: Exception) { "" }
            }
            if (phoneNumber.isBlank() && hasReadPhoneNumbers) {
                phoneNumber = try {
                    @Suppress("DEPRECATION", "MissingPermission")
                    tm.line1Number ?: ""
                } catch (e: Exception) { "" }
            }

            networkOperatorCode = try { tm.networkOperator ?: "" } catch (e: Exception) { "" }
            networkOperatorName = try { tm.networkOperatorName ?: "" } catch (e: Exception) { "" }
            isNetworkRoaming = try { tm.isNetworkRoaming } catch (e: Exception) { false }

            val networkTypeRaw = try {
                if (hasReadPhoneState) {
                    @Suppress("MissingPermission")
                    tm.dataNetworkType
                } else {
                    @Suppress("DEPRECATION")
                    tm.networkType
                }
            } catch (e: Exception) {
                TelephonyManager.NETWORK_TYPE_UNKNOWN
            }

            networkTypeStr = mapNetworkTypeToString(networkTypeRaw)
        }

        val rawSimState = telephonyManager?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN
        val simStateStr = when (rawSimState) {
            TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
            TelephonyManager.SIM_STATE_READY -> "READY"
            TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
            TelephonyManager.SIM_STATE_PERM_DISABLED -> "PERM_DISABLED"
            TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "CARD_IO_ERROR"
            TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "CARD_RESTRICTED"
            else -> "UNKNOWN"
        }

        if (simOperatorName.isBlank()) {
            simOperatorName = when (rawSimState) {
                TelephonyManager.SIM_STATE_ABSENT -> "No SIM Card"
                TelephonyManager.SIM_STATE_PIN_REQUIRED,
                TelephonyManager.SIM_STATE_PUK_REQUIRED -> "SIM Locked"
                TelephonyManager.SIM_STATE_NOT_READY -> "SIM Initializing"
                TelephonyManager.SIM_STATE_READY -> "Unknown Carrier"
                else -> "N/A"
            }
        }

        if (simOperator.isBlank()) simOperator = "N/A"
        if (simCountryIso.isBlank()) simCountryIso = "N/A"
        if (simSerialNumber.isBlank()) simSerialNumber = if (hasReadPhoneState) "N/A" else "Permission Required"
        if (phoneNumber.isBlank()) phoneNumber = if (hasReadPhoneNumbers) "N/A" else "Permission Required"

        val primarySimDetails = mapOf(
            "carrierName" to simOperatorName,
            "operatorCode" to simOperator,
            "countryIso" to simCountryIso,
            "phoneNumber" to phoneNumber,
            "simSerialNumber" to simSerialNumber,
            "simState" to simStateStr,
            "networkType" to networkTypeStr
        )

        val simDetailsStruct = mapOf(
            "activeSimCount" to activeSimCount,
            "simState" to simStateStr,
            "isNetworkRoaming" to isNetworkRoaming,
            "networkOperatorCode" to networkOperatorCode,
            "networkOperatorName" to networkOperatorName,
            "primarySim" to primarySimDetails,
            "simSlots" to simSlots
        )

        return mapOf(
            "simOperator" to simOperator,
            "simOperatorName" to simOperatorName,
            "simCountryIso" to simCountryIso,
            "simSerialNumber" to simSerialNumber,
            "phoneNumber" to phoneNumber,
            "networkType" to networkTypeStr,
            "simState" to simStateStr,
            "activeSimCount" to activeSimCount,
            "isNetworkRoaming" to isNetworkRoaming,
            "simInfo" to simDetailsStruct
        )
    }

    private fun mapNetworkTypeToString(type: Int): String {
        return when (type) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> "2G"

            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"

            TelephonyManager.NETWORK_TYPE_LTE -> "4G / LTE"
            20 /* TelephonyManager.NETWORK_TYPE_NR */ -> "5G"
            else -> "Unknown"
        }
    }

    /**
     * Gets active local IPv4 address across network interfaces.
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "N/A"
            for (intf in interfaces.toList()) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses.toList()) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: ""
                        if (host.isNotEmpty() && !host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error getting local IP: ${e.message}")
        }
        return "N/A"
    }

    /**
     * Fetches public IP address from external APIs with fallbacks.
     */
    suspend fun fetchPublicIpAddress(): String = withContext(Dispatchers.IO) {
        val endpoints = listOf(
            "https://api.ipify.org",
            "https://icanhazip.com",
            "https://api.my-ip.io/ip",
            "https://ifconfig.me/ip"
        )
        for (endpoint in endpoints) {
            try {
                val url = URL(endpoint)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (conn.responseCode == 200) {
                    val ip = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    if (ip.isNotEmpty() && (ip.contains(".") || ip.contains(":"))) {
                        conn.disconnect()
                        return@withContext ip
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }
        return@withContext "N/A"
    }

    /**
     * Collects real-time Wi-Fi status, connection state, SSID, BSSID, IP, signal level, link speed, frequency.
     */
    private fun getWifiStatusInfo(): Map<String, Any> {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        val isWifiEnabled = try { wifiManager?.isWifiEnabled ?: false } catch (e: Exception) { false }
        val rawWifiState = try { wifiManager?.wifiState ?: WifiManager.WIFI_STATE_UNKNOWN } catch (e: Exception) { WifiManager.WIFI_STATE_UNKNOWN }
        val wifiStateStr = when (rawWifiState) {
            WifiManager.WIFI_STATE_DISABLED -> "DISABLED"
            WifiManager.WIFI_STATE_DISABLING -> "DISABLING"
            WifiManager.WIFI_STATE_ENABLED -> "ENABLED"
            WifiManager.WIFI_STATE_ENABLING -> "ENABLING"
            else -> "UNKNOWN"
        }

        var isConnected = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager?.activeNetwork
            val caps = connectivityManager?.getNetworkCapabilities(activeNetwork)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                isConnected = true
            }
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager?.getNetworkInfo(ConnectivityManager.TYPE_WIFI)
            if (networkInfo != null && networkInfo.isConnected) {
                isConnected = true
            }
        }

        @Suppress("DEPRECATION")
        val wifiInfo: WifiInfo? = try {
            wifiManager?.connectionInfo
        } catch (e: Exception) {
            null
        }

        if (!isConnected && wifiInfo != null) {
            if (wifiInfo.networkId != -1 && wifiInfo.bssid != null && wifiInfo.bssid != "00:00:00:00:00:00" && wifiInfo.bssid != "02:00:00:00:00:00") {
                isConnected = true
            }
        }

        var rawSsid = try { wifiInfo?.ssid?.replace("\"", "")?.trim() ?: "" } catch (e: Exception) { "" }
        if (rawSsid == "<unknown ssid>" || rawSsid == "0x") rawSsid = ""

        if (rawSsid.isEmpty() && isConnected) {
            @Suppress("DEPRECATION")
            val netInfo = connectivityManager?.getNetworkInfo(ConnectivityManager.TYPE_WIFI)
            val extraInfo = try { netInfo?.extraInfo?.replace("\"", "")?.trim() ?: "" } catch (e: Exception) { "" }
            if (extraInfo.isNotEmpty() && extraInfo != "<unknown ssid>" && extraInfo != "0x") {
                rawSsid = extraInfo
            }
        }

        val bssid = try { wifiInfo?.bssid ?: "" } catch (e: Exception) { "" }
        val rssi = try { wifiInfo?.rssi ?: -127 } catch (e: Exception) { -127 }

        val signalLevel = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wifiManager?.calculateSignalLevel(rssi) ?: 0
            } else {
                @Suppress("DEPRECATION")
                WifiManager.calculateSignalLevel(rssi, 5)
            }
        } catch (e: Exception) { 0 }

        val linkSpeed = try { wifiInfo?.linkSpeed ?: -1 } catch (e: Exception) { -1 }
        val frequency = try { wifiInfo?.frequency ?: -1 } catch (e: Exception) { -1 }

        val localIpStr = getLocalIpAddress()
        val macAddress = try { wifiInfo?.macAddress ?: "N/A" } catch (e: Exception) { "N/A" }

        val displaySsid = if (rawSsid.isNotEmpty()) rawSsid else if (isConnected) "Connected Wi-Fi" else if (isWifiEnabled) "Wi-Fi On (Disconnected)" else "Wi-Fi Off"

        val wifiInfoMap = mapOf(
            "isEnabled" to isWifiEnabled,
            "state" to wifiStateStr,
            "isConnected" to isConnected,
            "ssid" to displaySsid,
            "bssid" to bssid.ifBlank { "N/A" },
            "rssi" to rssi,
            "signalLevel" to signalLevel,
            "linkSpeedMbps" to linkSpeed,
            "frequencyMhz" to frequency,
            "ipAddress" to localIpStr,
            "macAddress" to macAddress
        )

        return mapOf(
            "wifiEnabled" to isWifiEnabled,
            "wifiState" to wifiStateStr,
            "isWifiConnected" to isConnected,
            "wifiSsid" to displaySsid,
            "wifiBssid" to bssid.ifBlank { "N/A" },
            "wifiRssi" to rssi,
            "wifiSignalLevel" to signalLevel,
            "wifiLinkSpeedMbps" to linkSpeed,
            "wifiFrequencyMhz" to frequency,
            "wifiIpAddress" to localIpStr,
            "localIp" to localIpStr,
            "wifiInfo" to wifiInfoMap
        )
    }
}
