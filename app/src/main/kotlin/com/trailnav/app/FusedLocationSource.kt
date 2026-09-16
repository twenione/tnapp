package com.trailnav.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** Android adapter for the mandated one-Hz high-accuracy fused provider. */
class FusedLocationSource(context: Context) : LocationSource {
    private val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    override fun start(onLocation: (TrailLocation) -> Unit, onError: (Throwable) -> Unit) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setMaxUpdateDelayMillis(1_000L)
            .build()
        val listener = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { onLocation(it.toTrailLocation()) }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    onError(IllegalStateException("location unavailable"))
                }
            }
        }
        callback = listener
        client.requestLocationUpdates(request, listener, Looper.getMainLooper())
            .addOnFailureListener(onError)
    }

    override fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }

    private fun Location.toTrailLocation(): TrailLocation = TrailLocation(
        timestampMillis = time,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        speedMps = if (hasSpeed()) speed else null,
        bearingDegrees = if (hasBearing()) bearing else null,
        provider = provider ?: "fused",
    )
}
