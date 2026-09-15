package com.trailnav.app

import com.trailnav.core.SensorFrame

/** Location sample captured by the host provider before it enters the engine. */
data class TrailLocation(
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val speedMps: Float?,
    val bearingDegrees: Float?,
    val provider: String,
)

fun TrailLocation.toSensorFrame(): SensorFrame = SensorFrame(
    timestamp = timestampMillis,
    lat = latitude,
    lon = longitude,
    accuracy = accuracyMeters,
    speed = speedMps,
    gpsBearing = bearingDegrees,
)

/** Host abstraction used by the service and fake providers in JVM tests. */
interface LocationSource {
    fun start(onLocation: (TrailLocation) -> Unit, onError: (Throwable) -> Unit)
    fun stop()
}
