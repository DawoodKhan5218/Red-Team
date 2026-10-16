package com.example.data.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.local.AppDatabase
import com.example.data.local.LocationLogEntity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class LocationSyncManager(private val context: Context) {

    companion object {
        private const val TAG = "LocationSyncManager"
        private const val LOCATION_TIMEOUT_MS = 15000L // 15 seconds max wait time
    }

    private val db = AppDatabase.getInstance(context)
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    suspend fun captureAndSyncLocation(): LocationLogEntity = withContext(Dispatchers.IO) {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        var bestLocation: Location? = null
        var acquisitionSource = "none"

        if (hasFine || hasCoarse) {
            // --- TIER 1: Active Fused High-Accuracy Request ---
            try {
                bestLocation = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
                    suspendCancellableCoroutine { continuation ->
                        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
                            .setWaitForAccurateLocation(true)
                            .setMaxUpdateAgeMillis(0)
                            .build()

                        val locationCallback = object : LocationCallback() {
                            override fun onLocationResult(result: LocationResult) {
                                val loc = result.lastLocation
                                if (loc != null) {
                                    try {
                                        fusedLocationClient.removeLocationUpdates(this)
                                    } catch (_: Exception) {}
                                    if (continuation.isActive) {
                                        continuation.resume(loc)
                                    }
                                }
                            }
                        }

                        continuation.invokeOnCancellation {
                            try {
                                fusedLocationClient.removeLocationUpdates(locationCallback)
                            } catch (_: Exception) {}
                        }

                        try {
                            fusedLocationClient.requestLocationUpdates(
                                locationRequest,
                                locationCallback,
                                Looper.getMainLooper()
                            ).addOnFailureListener { e ->
                                Log.w(TAG, "Fused location request failed: ${e.message}")
                                if (continuation.isActive) continuation.resume(null)
                            }
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Security exception requesting fused location: ${e.message}")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                }
                if (bestLocation != null) {
                    acquisitionSource = "fused_active_gps"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tier 1 active fused fix timed out or failed: ${e.message}")
            }

            // --- TIER 2: Last Known Fused Location Fallback ---
            if (bestLocation == null) {
                try {
                    bestLocation = suspendCancellableCoroutine { continuation ->
                        fusedLocationClient.lastLocation
                            .addOnSuccessListener { loc ->
                                continuation.resume(loc)
                            }
                            .addOnFailureListener {
                                continuation.resume(null)
                            }
                    }
                    if (bestLocation != null) {
                        acquisitionSource = "fused_last_known"
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Tier 2 last known fused location error: ${e.message}")
                }
            }
        }

        // Build final entity result
        val entity = if (bestLocation != null && bestLocation.latitude != 0.0 && bestLocation.longitude != 0.0) {
            Log.d(TAG, "Location successfully acquired via [$acquisitionSource]: Lat=${bestLocation.latitude}, Lon=${bestLocation.longitude}")
            LocationLogEntity(
                latitude = bestLocation.latitude,
                longitude = bestLocation.longitude,
                altitude = bestLocation.altitude,
                speed = bestLocation.speed,
                bearing = bestLocation.bearing,
                accuracy = bestLocation.accuracy,
                provider = bestLocation.provider ?: acquisitionSource,
                source = acquisitionSource,
                timestamp = System.currentTimeMillis(),
                isSynced = true
            )
        } else {
            Log.w(TAG, "Fused location acquisition failed. Returning default marker.")
            LocationLogEntity(
                latitude = 0.0,
                longitude = 0.0,
                altitude = 0.0,
                speed = 0f,
                bearing = 0f,
                accuracy = 0f,
                provider = if (hasFine) "Fused Location Timed Out" else "Location Permission Missing",
                source = "none",
                timestamp = System.currentTimeMillis(),
                isSynced = false
            )
        }

        // Save valid fixes to Room Database
        try {
            if (entity.latitude != 0.0 && entity.longitude != 0.0) {
                db.locationDao().insertLocation(entity)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Database insertion error for location entity: ${e.message}")
        }

        entity
    }
}
