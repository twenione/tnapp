package com.trailnav.app

import com.trailnav.core.GuideConfig
import com.trailnav.core.RouteModel

data class RouteStartupUpdate(
    val stage: RoutePreparationStage,
    val observation: RouteDirectionObservation,
    val orientation: RouteOrientationResult? = null,
    val replayLocations: List<TrailLocation> = emptyList(),
)

/** Buffers initial fixes until the route direction is known, without dropping them. */
class RouteStartupCoordinator(
    originalGpxXml: String,
    config: GuideConfig = GuideConfig(),
    startupAtMillis: Long? = null,
) {
    private val originalRoute = RouteModel.fromGpx(originalGpxXml, config)
    private val observer = RouteDirectionObserver(originalRoute, config, startupAtMillis = startupAtMillis)
    private val bufferedLocations = mutableListOf<TrailLocation>()
    private val gpxXml = originalGpxXml
    private var resolved = false

    fun accept(location: TrailLocation, observedAtMillis: Long = location.timestampMillis): RouteStartupUpdate {
        check(!resolved) { "route startup direction is already resolved" }
        bufferedLocations += location
        return toUpdate(observer.observe(location, observedAtMillis))
    }

    fun timeout(observedAtMillis: Long): RouteStartupUpdate {
        check(!resolved) { "route startup direction is already resolved" }
        return toUpdate(observer.timeout(observedAtMillis))
    }

    private fun toUpdate(observation: RouteDirectionObservation): RouteStartupUpdate {
        if (!observation.resolved) {
            val stage = when (observation.outcome) {
                RouteDirectionObservationOutcome.START_FOUND -> RoutePreparationStage.START_FOUND
                else -> RoutePreparationStage.PREPARING
            }
            return RouteStartupUpdate(stage = stage, observation = observation)
        }
        resolved = true
        val reason = when (observation.outcome) {
            RouteDirectionObservationOutcome.FORWARD,
            RouteDirectionObservationOutcome.REVERSE -> "direction-observed"
            RouteDirectionObservationOutcome.FALLBACK_TIMEOUT -> "fallback-timeout"
            RouteDirectionObservationOutcome.FALLBACK_NO_NET_DISPLACEMENT -> "fallback-no-net-displacement"
            else -> error("unresolved observation cannot complete startup")
        }
        val orientation = RouteOrientation.orient(
            gpxXml = gpxXml,
            direction = observation.direction,
            reason = reason,
            observedNetDisplacementMeters = kotlin.math.abs(observation.netDisplacementMeters),
            observationElapsedSeconds = observation.elapsedSeconds,
            fallbackLocation = if (observation.direction == null) bufferedLocations.lastOrNull() else null,
        )
        val replay = bufferedLocations.toList()
        bufferedLocations.clear()
        return RouteStartupUpdate(
            stage = RoutePreparationStage.DIRECTION_CONFIRMED,
            observation = observation,
            orientation = orientation,
            replayLocations = replay,
        )
    }
}
