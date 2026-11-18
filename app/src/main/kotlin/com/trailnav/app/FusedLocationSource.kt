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

/** Android adapter for the high-accuracy fused provider. */
class FusedLocationSource(context: Context) : LocationSource {
    private val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private var callback: LocationCallback? = null
    private var onLocation: ((TrailLocation) -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null
    private var intervalMillis = 1_000L

    @SuppressLint("MissingPermission")
    override fun start(onLocation: (TrailLocation) -> Unit, onError: (Throwable) -> Unit) {
        this.onLocation = onLocation
        this.onError = onError
        requestLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    override fun setUpdateIntervalMillis(intervalMillis: Long) {
        if (this.intervalMillis == intervalMillis) return
        this.intervalMillis = intervalMillis
        if (callback != null) {
            callback?.let(client::removeLocationUpdates)
            callback = null
            requestLocationUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        val locationHandler = onLocation ?: return
        val errorHandler = onError ?: return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis)
            .setMaxUpdateDelayMillis(intervalMillis)
            .build()
        val listener = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { locationHandler(it.toTrailLocation()) }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    errorHandler(IllegalStateException("location unavailable"))
                }
            }
        }
        callback = listener
        client.requestLocationUpdates(request, listener, Looper.getMainLooper())
            .addOnFailureListener(errorHandler)
    }

    override fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
        onLocation = null
        onError = null
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
