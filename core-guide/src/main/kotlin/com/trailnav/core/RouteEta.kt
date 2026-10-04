package com.trailnav.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

enum class TargetKind {
    NEXT_SUMMIT,
    DESTINATION,
}

data class RouteTargetEstimate(
    val kind: TargetKind,
    val source: String,
    val remainingMeters: Double,
    val remainingSeconds: Double?,
    val correction: Double,
    val correctionActive: Boolean,
    val slopeSource: String,
)

private data class RouteEtaObservation(
    val endTimestampMillis: Long,
    val elapsedSeconds: Double,
    val distanceMeters: Double,
    val predictedSeconds: Double,
)

/** Session-local travel-time calibration and route-target estimator. */
class RouteEtaTracker(
    private val route: RouteModel,
    private val config: GuideConfig = GuideConfig(),
) {
    private data class Fix(
        val timestampMillis: Long,
        val projectedMeters: Double,
        val direction: ProgressDirection,
        val onRoute: Boolean,
    )

    private data class TargetCandidate(
        val s: Double,
        val source: String,
    )

    private val totalLengthMeters = route.totalLengthMeters.coerceAtLeast(0.0)
    private val hasUsableElevation =
        route.elevationUse.used &&
            route.points.size == route.cumulativeMeters.size &&
            route.points.size == route.smoothedElevationMeters.size
    private val observations = mutableListOf<RouteEtaObservation>()
    private var previousFix: Fix? = null

    fun observe(
        timestampMillis: Long,
        projectedMeters: Double,
        direction: ProgressDirection,
        onRoute: Boolean,
    ) {
        val current = Fix(
            timestampMillis = timestampMillis,
            projectedMeters = clampProgress(projectedMeters),
            direction = direction,
            onRoute = onRoute,
        )
        val previous = previousFix
        previousFix = current
        trimWindow(timestampMillis)
        if (previous == null) return

        val elapsedMillis = timestampMillis - previous.timestampMillis
        if (elapsedMillis <= 0L) return
        val elapsedSeconds = elapsedMillis / 1_000.0
        if (elapsedSeconds > MAX_OBSERVATION_GAP_SECONDS) return
        if (previous.direction != ProgressDirection.FORWARD || direction != ProgressDirection.FORWARD) return
        if (!previous.onRoute || !onRoute) return

        val distanceMeters = current.projectedMeters - previous.projectedMeters
        val movingSegment =
            distanceMeters > 0.0 && distanceMeters / elapsedSeconds >= config.etaMinMovingSpeedMps
        if (!movingSegment) return

        observations += RouteEtaObservation(
            endTimestampMillis = timestampMillis,
            elapsedSeconds = elapsedSeconds,
            distanceMeters = distanceMeters,
            predictedSeconds = predictTravelSeconds(previous.projectedMeters, current.projectedMeters),
        )
        trimWindow(timestampMillis)
    }

    fun estimate(
        projectedMeters: Double,
        direction: ProgressDirection,
        arrived: Boolean,
    ): RouteTargetEstimate? {
        if (direction == ProgressDirection.REVERSE) return null

        val progressMeters = clampProgress(projectedMeters)
        val correction = correctionSnapshot()
        if (arrived) {
            return RouteTargetEstimate(
                kind = TargetKind.DESTINATION,
                source = DESTINATION_SOURCE,
                remainingMeters = 0.0,
                remainingSeconds = 0.0,
                correction = correction.first,
                correctionActive = correction.second,
                slopeSource = slopeSource(),
            )
        }

        val targets = targetCandidates()
        val switchMeters = summitSwitchMeters(targets)
        val candidate = if (progressMeters >= switchMeters) {
            null
        } else {
            targets.firstOrNull { it.s > progressMeters }
        }
        if (candidate == null) {
            return destinationEstimate(progressMeters, correction)
        }

        return RouteTargetEstimate(
            kind = TargetKind.NEXT_SUMMIT,
            source = candidate.source,
            remainingMeters = (candidate.s - progressMeters).coerceAtLeast(0.0),
            remainingSeconds = predictTravelSeconds(progressMeters, candidate.s) * correction.first,
            correction = correction.first,
            correctionActive = correction.second,
            slopeSource = slopeSource(),
        )
    }

    private fun destinationEstimate(
        progressMeters: Double,
        correction: Pair<Double, Boolean>,
    ): RouteTargetEstimate = RouteTargetEstimate(
        kind = TargetKind.DESTINATION,
        source = DESTINATION_SOURCE,
        remainingMeters = (totalLengthMeters - progressMeters).coerceAtLeast(0.0),
        remainingSeconds = predictTravelSeconds(progressMeters, totalLengthMeters) * correction.first,
        correction = correction.first,
        correctionActive = correction.second,
        slopeSource = slopeSource(),
    )

    private fun targetCandidates(): List<TargetCandidate> =
        if (route.waypoints.isNotEmpty()) {
            route.waypoints
                .map { TargetCandidate(it.s, WAYPOINT_SOURCE) }
                .sortedBy { it.s }
        } else {
            route.peaks
                .map { TargetCandidate(it.s, ELEVATION_PEAK_SOURCE) }
                .sortedBy { it.s }
        }

    private fun summitSwitchMeters(targets: List<TargetCandidate>): Double {
        if (!hasUsableElevation) return targets.lastOrNull()?.s ?: 0.0
        val summitIndex = route.smoothedElevationMeters.indices
            .maxByOrNull { route.smoothedElevationMeters[it] }
            ?: return 0.0
        return route.cumulativeMeters[summitIndex]
    }

    private fun correctionSnapshot(): Pair<Double, Boolean> {
        val windowDistanceMeters = observations.sumOf { it.distanceMeters }
        val predictedSeconds = observations.sumOf { it.predictedSeconds }
        val elapsedSeconds = observations.sumOf { it.elapsedSeconds }
        if (windowDistanceMeters < config.etaMinWindowMeters || predictedSeconds <= 0.0 || !predictedSeconds.isFinite()) {
            return 1.0 to false
        }
        return (elapsedSeconds / predictedSeconds)
            .coerceIn(config.etaCorrectionMin, config.etaCorrectionMax) to true
    }

    private fun trimWindow(nowMillis: Long) {
        observations.removeAll { observation ->
            (nowMillis - observation.endTimestampMillis) / 1_000.0 > config.etaWindowSeconds
        }
    }

    private fun clampProgress(projectedMeters: Double): Double =
        projectedMeters.coerceIn(0.0, totalLengthMeters)

    private fun slopeSource(): String = if (hasUsableElevation) "tobler" else "flat"

    private fun predictTravelSeconds(fromMeters: Double, toMeters: Double): Double {
        val startMeters = clampProgress(fromMeters)
        val endMeters = clampProgress(toMeters)
        if (endMeters <= startMeters || route.points.size < 2 || route.cumulativeMeters.size != route.points.size) {
            return 0.0
        }

        var seconds = 0.0
        for (index in 0 until route.points.lastIndex) {
            val segmentStartMeters = route.cumulativeMeters[index]
            val segmentEndMeters = route.cumulativeMeters[index + 1]
            val segmentLengthMeters = segmentEndMeters - segmentStartMeters
            if (segmentLengthMeters <= 0.0) continue

            val overlapStart = max(startMeters, segmentStartMeters)
            val overlapEnd = min(endMeters, segmentEndMeters)
            val overlapMeters = overlapEnd - overlapStart
            if (overlapMeters <= 0.0) continue

            val elevationDelta = if (hasUsableElevation) {
                route.smoothedElevationMeters[index + 1] - route.smoothedElevationMeters[index]
            } else {
                0.0
            }
            val slope = elevationDelta / segmentLengthMeters
            val speedMetersPerSecond = toblerSpeedMetersPerSecond(slope)
            if (speedMetersPerSecond <= 0.0 || !speedMetersPerSecond.isFinite()) {
                return Double.POSITIVE_INFINITY
            }
            seconds += overlapMeters / speedMetersPerSecond
        }
        return seconds
    }

    private companion object {
        const val MAX_OBSERVATION_GAP_SECONDS = 60.0
        const val WAYPOINT_SOURCE = "waypoint"
        const val ELEVATION_PEAK_SOURCE = "elevation-peak"
        const val DESTINATION_SOURCE = "destination"
    }
}

internal fun toblerSpeedMetersPerSecond(slope: Double): Double =
    (6.0 * exp(-3.5 * abs(slope + 0.05))) / 3.6
