package com.example.data.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.example.data.local.AppDatabase
import com.example.data.local.LocationLogEntity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class LocationSyncManager(private val context: Context) {

    private val db = AppDatabase.getInstance(context)

    suspend fun captureAndSyncLocation(): LocationLogEntity = withContext(Dispatchers.IO) {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        var bestLocation: Location? = null

        if (hasFine || hasCoarse) {
            try {
                val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                val tokenSource = CancellationTokenSource()
                bestLocation = fusedClient.getCurrentLocation(
                    if (hasFine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    tokenSource.token
                ).await()
            } catch (_: Exception) {}

            if (bestLocation == null) {
                try {
                    val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                    bestLocation = fusedClient.lastLocation.await()
                } catch (_: Exception) {}
            }

            if (bestLocation == null) {
                try {
                    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val gpsLoc = if (hasFine) locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null
                    val netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    val passiveLoc = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)

                    bestLocation = listOfNotNull(gpsLoc, netLoc, passiveLoc).maxByOrNull { it.time }
                } catch (_: Exception) {}
            }
        }

        val entity = if (bestLocation != null) {
            LocationLogEntity(
                latitude = bestLocation.latitude,
                longitude = bestLocation.longitude,
                altitude = bestLocation.altitude,
                speed = bestLocation.speed,
                bearing = bestLocation.bearing,
                accuracy = bestLocation.accuracy,
                provider = bestLocation.provider ?: "GPS_NETWORK",
                source = "gps",
                timestamp = System.currentTimeMillis(),
                isSynced = true
            )
        } else {
            // Fallback to Cell Tower location if GPS / Fused location is unavailable
            val cellLoc = CellLocationProvider.getCellLocation(context)
            if (cellLoc != null) {
                LocationLogEntity(
                    latitude = cellLoc.latitude,
                    longitude = cellLoc.longitude,
                    altitude = 0.0,
                    speed = 0f,
                    bearing = 0f,
                    accuracy = cellLoc.accuracy,
                    provider = "CELL_TOWER",
                    source = "cell_tower",
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
                    provider = if (hasFine || hasCoarse) "Searching for Location Fix" else "Location Permission Required",
                    source = "none",
                    timestamp = System.currentTimeMillis(),
                    isSynced = false
                )
            }
        }

        try {
            if (entity.latitude != 0.0 || entity.longitude != 0.0) {
                db.locationDao().insertLocation(entity)
            }
        } catch (_: Exception) {}

        entity
    }
}
