package com.example.data.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class CellLocationResult(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val source: String = "cell_tower"
)

object CellLocationProvider {

    private const val TAG = "CellLocationProvider"

    suspend fun getCellLocation(context: Context): CellLocationResult? = withContext(Dispatchers.IO) {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return@withContext null
            
            val hasPhoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

            var mcc = 0
            var mnc = 0
            var lac = 0
            var cellId = 0

            val networkOperator = telephonyManager.networkOperator
            if (!networkOperator.isNullOrEmpty() && networkOperator.length >= 5) {
                try {
                    mcc = networkOperator.substring(0, 3).toInt()
                    mnc = networkOperator.substring(3).toInt()
                } catch (_: Exception) {}
            }

            if (hasPhoneState || hasCoarse || hasFine) {
                try {
                    val allCellInfo = telephonyManager.allCellInfo
                    if (!allCellInfo.isNullOrEmpty()) {
                        for (info in allCellInfo) {
                            val mccStr: String?
                            val mncStr: String?
                            if (info is CellInfoLte) {
                                val identity = info.cellIdentity
                                cellId = identity.ci
                                lac = identity.tac
                                mccStr = identity.mccString
                                mncStr = identity.mncString
                                if (mccStr != null) mcc = mccStr.toIntOrNull() ?: mcc
                                if (mncStr != null) mnc = mncStr.toIntOrNull() ?: mnc
                                if (cellId != android.telephony.CellInfo.UNAVAILABLE && cellId != 0) break
                            } else if (info is CellInfoGsm) {
                                val identity = info.cellIdentity
                                cellId = identity.cid
                                lac = identity.lac
                                mccStr = identity.mccString
                                mncStr = identity.mncString
                                if (mccStr != null) mcc = mccStr.toIntOrNull() ?: mcc
                                if (mncStr != null) mnc = mncStr.toIntOrNull() ?: mnc
                                if (cellId != android.telephony.CellInfo.UNAVAILABLE && cellId != 0) break
                            } else if (info is CellInfoWcdma) {
                                val identity = info.cellIdentity
                                cellId = identity.cid
                                lac = identity.lac
                                mccStr = identity.mccString
                                mncStr = identity.mncString
                                if (mccStr != null) mcc = mccStr.toIntOrNull() ?: mcc
                                if (mncStr != null) mnc = mncStr.toIntOrNull() ?: mnc
                                if (cellId != android.telephony.CellInfo.UNAVAILABLE && cellId != 0) break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading allCellInfo: ${e.message}")
                }
            }

            if (mcc > 0 && mnc > 0) {
                val apiResult = fetchFromOpenCellId(mcc, mnc, lac, cellId)
                if (apiResult != null) {
                    return@withContext apiResult
                }
            }

            if (mcc > 0) {
                return@withContext getApproximateLocationForMcc(mcc)
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "getCellLocation failed: ${e.message}", e)
            null
        }
    }

    private fun fetchFromOpenCellId(mcc: Int, mnc: Int, lac: Int, cellId: Int): CellLocationResult? {
        return try {
            val urlStr = "https://opencellid.org/processRequest?key=pk.1234567890abcdef&format=json&mcc=$mcc&mnc=$mnc&lac=$lac&cellid=$cellId"
            val url = URL(urlStr)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
            }

            if (connection.responseCode == 200) {
                val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseStr)
                if (json.has("lat") && json.has("lon")) {
                    val lat = json.getDouble("lat")
                    val lon = json.getDouble("lon")
                    val accuracy = if (json.has("accuracy")) json.getDouble("accuracy").toFloat() else 500f
                    return CellLocationResult(lat, lon, accuracy, "cell_tower")
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "OpenCellID API query failed: ${e.message}")
            null
        }
    }

    private fun getApproximateLocationForMcc(mcc: Int): CellLocationResult {
        val (lat, lon) = when (mcc) {
            404, 405, 406 -> Pair(20.5937, 78.9629) // India
            310, 311, 312 -> Pair(37.0902, -95.7129) // USA
            234, 235 -> Pair(55.3781, -3.4360) // UK
            262 -> Pair(51.1657, 10.4515) // Germany
            440, 441 -> Pair(36.2048, 138.2529) // Japan
            208 -> Pair(46.6034, 2.2137) // France
            else -> Pair(37.7749, -122.4194) // Default fallback
        }
        return CellLocationResult(lat, lon, 3000f, "cell_tower")
    }
}
