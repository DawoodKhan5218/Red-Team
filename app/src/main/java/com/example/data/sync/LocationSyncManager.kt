package com.example.data.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.data.local.AppDatabase
import com.example.data.local.LocationLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class LocationSyncManager(private val context: Context) {

    private val db = AppDatabase.getInstance(context)

    suspend fun captureAndSyncLocation(): LocationLogEntity = withContext(Dispatchers.IO) {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        var bestLocation: Location? = null

        if (hasFine || hasCoarse) {
            val locationManager = try {
                context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            } catch (_: Exception) { null }

            if (locationManager != null) {
                // 1. Try to get current location via standard Android framework
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasFine) {
                        bestLocation = withTimeoutOrNull(4000L) {
                            suspendCancellableCoroutine { continuation ->
                                val cancellationSignal = CancellationSignal()
                                continuation.invokeOnCancellation { cancellationSignal.cancel() }
                                try {
                                    locationManager.getCurrentLocation(
                                        LocationManager.GPS_PROVIDER,
                                        cancellationSignal,
                                        context.mainExecutor
                                    ) { loc ->
                                        if (continuation.isActive) {
                                            continuation.resume(loc)
                                        }
                                    }
                                } catch (_: Exception) {
                                    if (continuation.isActive) continuation.resume(null)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}

                // 2. Fallback to active single GPS update if still null
                if (bestLocation == null && hasFine) {
                    try {
                        bestLocation = withTimeoutOrNull(3000L) {
                            suspendCancellableCoroutine { continuation ->
                                val listener = object : LocationListener {
                                    override fun onLocationChanged(location: Location) {
                                        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
                                        if (continuation.isActive) continuation.resume(location)
                                    }
                                    @Deprecated("Deprecated in Java")
                                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                                    override fun onProviderEnabled(provider: String) {}
                                    override fun onProviderDisabled(provider: String) {}
                                }
                                continuation.invokeOnCancellation {
                                    try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
                                }
                                try {
                                    if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                                        locationManager.requestSingleUpdate(
                                            LocationManager.GPS_PROVIDER,
                                            listener,
                                            Looper.getMainLooper()
                                        )
                                    } else {
                                        continuation.resume(null)
                                    }
                                } catch (_: Exception) {
                                    if (continuation.isActive) continuation.resume(null)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                // 3. Fallback to last known GPS location
                if (bestLocation == null) {
                    try {
                        val gpsLoc = if (hasFine) locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null
                        val passiveLoc = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)

                        bestLocation = listOfNotNull(gpsLoc, passiveLoc)
                            .filter { it.provider != LocationManager.NETWORK_PROVIDER }
                            .maxByOrNull { it.time }
                    } catch (_: Exception) {}
                }
            }
        }

        // Ensure cellular/network-derived location is excluded
        if (bestLocation?.provider == LocationManager.NETWORK_PROVIDER) {
            bestLocation = null
        }

        val entity = if (bestLocation != null) {
            LocationLogEntity(
                latitude = bestLocation.latitude,
                longitude = bestLocation.longitude,
                altitude = bestLocation.altitude,
                speed = bestLocation.speed,
                bearing = bestLocation.bearing,
                accuracy = bestLocation.accuracy,
                provider = bestLocation.provider ?: "GPS",
                source = "gps",
                timestamp = System.currentTimeMillis(),
                isSynced = true
            )
        } else {
            LocationLogEntity(
                latitude = 0.0,
                longitude = 0.0,
                altitude = 0.0,
                speed = 0f,
                bearing = 0f,
                accuracy = 0f,
                provider = if (hasFine) "Searching for GPS Fix" else "GPS Permission Required",
                source = "none",
                timestamp = System.currentTimeMillis(),
                isSynced = false
            )
        }

        try {
            if (entity.latitude != 0.0 || entity.longitude != 0.0) {
                db.locationDao().insertLocation(entity)
            }
        } catch (_: Exception) {}

        entity
    }
}
