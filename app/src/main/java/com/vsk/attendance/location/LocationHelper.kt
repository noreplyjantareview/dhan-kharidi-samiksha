package com.vsk.attendance.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class LocationHelper(context: Context) {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    /** One-shot high-accuracy fix. Requires location permission to be granted. */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): Location? =
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()

    /**
     * Continuous updates while the app is in the foreground. Emits an initial
     * cached fix (if any) then live fixes every [intervalMs].
     */
    @SuppressLint("MissingPermission")
    fun locationUpdates(intervalMs: Long = 20_000L): Flow<Location> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateDistanceMeters(5f)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it) }
            }
        }

        client.lastLocation.await()?.let { trySend(it) }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())

        awaitClose { client.removeLocationUpdates(callback) }
    }
}
