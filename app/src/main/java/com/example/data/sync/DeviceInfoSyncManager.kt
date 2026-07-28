package com.example.data.sync

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.example.data.local.AppDatabase
import com.example.data.local.DeviceInfoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

class DeviceInfoSyncManager(private val context: Context) {

    private val db = AppDatabase.getInstance(context)

    @SuppressLint("HardwareIds", "MissingPermission")
    suspend fun collectAndSyncDeviceInfo(): DeviceInfoEntity = withContext(Dispatchers.IO) {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val simState = telephonyManager.simState
        val simStateStr = when (simState) {
            TelephonyManager.SIM_STATE_READY -> "Ready"
            TelephonyManager.SIM_STATE_ABSENT -> "Absent (No SIM)"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN Locked"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK Locked"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network Locked"
            else -> "Not Ready / Unknown"
        }

        var carrierName = "N/A"
        var countryIso = "N/A"
        var mccMnc = "N/A"
        var iccid = "N/A"
        var subId = -1
        var phoneNumber = "N/A"

        if (simState == TelephonyManager.SIM_STATE_READY) {
            val simOpName = telephonyManager.simOperatorName
            val netOpName = telephonyManager.networkOperatorName
            carrierName = simOpName.ifEmpty { netOpName.ifEmpty { "Active Carrier" } }

            val simCountry = telephonyManager.simCountryIso.uppercase()
            val netCountry = telephonyManager.networkCountryIso.uppercase()
            countryIso = simCountry.ifEmpty { netCountry.ifEmpty { "N/A" } }

            val operatorCode = telephonyManager.simOperator.ifEmpty { telephonyManager.networkOperator }
            mccMnc = if (operatorCode.length >= 5) {
                "${operatorCode.substring(0, 3)} / ${operatorCode.substring(3)}"
            } else {
                "N/A"
            }
        } else if (simState == TelephonyManager.SIM_STATE_ABSENT) {
            carrierName = "No SIM Card"
        }

        try {
            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val activeSubList = subManager?.activeSubscriptionInfoList
            if (!activeSubList.isNullOrEmpty()) {
                val carriers = activeSubList.mapNotNull { 
                    it.carrierName?.toString()?.ifEmpty { null } ?: it.displayName?.toString()?.ifEmpty { null } 
                }.distinct()
                if (carriers.isNotEmpty()) {
                    carrierName = carriers.joinToString(" / ")
                }

                val numbers = activeSubList.mapNotNull { it.number?.ifEmpty { null } }.distinct()
                if (numbers.isNotEmpty()) {
                    phoneNumber = numbers.joinToString(" / ")
                } else {
                    val firstSub = activeSubList.first()
                    phoneNumber = firstSub.number?.ifEmpty { "N/A" } ?: "N/A"
                }

                val subInfo = activeSubList.first()
                subId = subInfo.subscriptionId
                iccid = subInfo.iccId ?: "N/A"
            }
        } catch (_: Exception) {}

        if (phoneNumber == "N/A") {
            try {
                val line1 = telephonyManager.line1Number
                if (!line1.isNullOrEmpty()) {
                    phoneNumber = line1
                }
            } catch (_: Exception) {}
        }

        val phoneTypeStr = when (telephonyManager.phoneType) {
            TelephonyManager.PHONE_TYPE_GSM -> "GSM"
            TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
            TelephonyManager.PHONE_TYPE_SIP -> "SIP"
            else -> "None / Digital"
        }

        val isRoaming = telephonyManager.isNetworkRoaming

        val dataStateStr = when (telephonyManager.dataState) {
            TelephonyManager.DATA_CONNECTED -> "Connected"
            TelephonyManager.DATA_CONNECTING -> "Connecting"
            TelephonyManager.DATA_DISCONNECTED -> "Disconnected"
            TelephonyManager.DATA_SUSPENDED -> "Suspended"
            else -> "Unknown"
        }

        // Network Type
        val networkTypeStr = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNet = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNet)
            when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi Network"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular Mobile Data"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet Network"
                else -> "No Active Data Connection"
            }
        } catch (_: Exception) {
            "Unknown"
        }

        // Battery Info
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 0
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val chargePlug = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val chargeTypeStr = when (chargePlug) {
            BatteryManager.BATTERY_PLUGGED_USB -> "USB Port"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC Wall Charger"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless Charger"
            else -> if (isCharging) "Battery Charging" else "Discharging"
        }

        // Storage Info
        val statFs = StatFs(Environment.getDataDirectory().path)
        val totalStorageMb = (statFs.totalBytes / (1024 * 1024))
        val freeStorageMb = (statFs.availableBytes / (1024 * 1024))

        // Wi-Fi Info
        val wifiInfo = wifiManager.connectionInfo
        val rawSsid = wifiInfo?.ssid?.replace("\"", "")
        val wifiSsid = if (!rawSsid.isNullOrEmpty() && rawSsid != "<unknown ssid>") rawSsid else "Not Connected"
        val wifiBssid = wifiInfo?.bssid?.takeIf { it != "02:00:00:00:00:00" } ?: "N/A"
        val wifiRssi = if (wifiSsid != "Not Connected") (wifiInfo?.rssi ?: 0) else 0
        val localIp = getLocalIpAddress()
        val macAddress = getMacAddress()

        val deviceInfo = DeviceInfoEntity(
            id = 1,
            simState = simStateStr,
            carrierName = carrierName,
            countryIso = countryIso,
            mccMnc = mccMnc,
            iccid = iccid,
            subscriptionId = subId,
            phoneNumber = phoneNumber,
            phoneType = phoneTypeStr,
            networkType = networkTypeStr,
            isRoaming = isRoaming,
            dataState = dataStateStr,
            batteryLevel = batteryPct,
            isCharging = isCharging,
            chargeType = chargeTypeStr,
            totalStorageMb = totalStorageMb,
            freeStorageMb = freeStorageMb,
            wifiSsid = wifiSsid,
            wifiBssid = wifiBssid,
            wifiRssi = wifiRssi,
            localIp = localIp,
            macAddress = macAddress,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            lastUpdated = System.currentTimeMillis()
        )

        db.deviceInfoDao().saveDeviceInfo(deviceInfo)
        deviceInfo
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: "Unavailable"
                    }
                }
            }
        } catch (_: Exception) {}
        return "Not Connected"
    }

    private fun getMacAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.name.equals("wlan0", ignoreCase = true)) {
                    val macBytes = networkInterface.hardwareAddress ?: return "Restricted by OS"
                    val res = StringBuilder()
                    for (b in macBytes) {
                        res.append(String.format("%02X:", b))
                    }
                    if (res.isNotEmpty()) res.deleteCharAt(res.length - 1)
                    return res.toString()
                }
            }
        } catch (_: Exception) {}
        return "Restricted by OS"
    }
}
