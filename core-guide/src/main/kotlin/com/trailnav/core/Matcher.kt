package com.trailnav.core

import kotlin.math.abs

internal object RouteMatcher {
    fun nearest(route: RouteModel, frame: SensorFrame, previousS: Double?, config: GuideConfig): MatchResult? {
        if (route.points.size < 2) return null
        val point = route.toEnu(GeoPoint(frame.lat, frame.lon))
        val candidates = candidateSegments(route, point, previousS, config)
        if (candidates.isEmpty()) return null
        var best: MatchResult? = null
        for (segment in candidates) {
            if (segment !in 0 until route.points.lastIndex) continue
            val projection = RouteMath.segmentProjection(point, route.points[segment], route.points[segment + 1])
            val projectedS = route.cumulativeMeters[segment] +
                (route.cumulativeMeters[segment + 1] - route.cumulativeMeters[segment]) * projection.fraction
            val candidate = MatchResult(
                distanceMeters = projection.distanceMeters,
                projectedMeters = projectedS,
                segmentIndex = segment,
                projectedPoint = projection.point
            )
            if (best == null || candidate.distanceMeters < best.distanceMeters ||
                (candidate.distanceMeters == best.distanceMeters && candidate.projectedMeters < best.projectedMeters)) {
                best = candidate
            }
        }
        return best
    }

    private fun candidateSegments(
        route: RouteModel,
        point: EnuPoint,
        previousS: Double?,
        config: GuideConfig
    ): Set<Int> {
        if (previousS == null) return route.points.indices.take(route.points.size - 1).toSet()
        val lower = previousS - config.searchWindowMeters
        val upper = previousS + config.searchWindowMeters
        val indexed = route.spatialIndex.candidateSegments(point, config.searchWindowMeters)
        val bounded = indexed.filterTo(linkedSetOf()) { index ->
            index in 0 until route.points.lastIndex &&
                route.cumulativeMeters[index + 1] >= lower && route.cumulativeMeters[index] <= upper
        }
        if (bounded.isNotEmpty()) return bounded
        // Keep the ±200 m invariant even when a point lies just outside an
        // index cell.  The first frame is the only full-route search.
        return route.points.indices.take(route.points.size - 1).filterTo(linkedSetOf()) { index ->
            route.cumulativeMeters[index + 1] >= lower && route.cumulativeMeters[index] <= upper
        }
    }

    fun withDirection(previous: GuideState, match: MatchResult, frame: SensorFrame, config: GuideConfig): MatchResult {
        val previousMatch = previous.lastMatch ?: return match.copy(direction = ProgressDirection.UNKNOWN)
        val delta = match.projectedMeters - previousMatch.projectedMeters
        val ema = config.emaAlpha * delta + (1.0 - config.emaAlpha) * previous.emaDeltaMeters
        val stationary = frame.speed != null && frame.speed.toDouble() < config.stationarySpeedMps
        val direction = when {
            stationary || abs(ema) < 0.01 -> ProgressDirection.STATIONARY
            ema > 0.0 -> ProgressDirection.FORWARD
            else -> ProgressDirection.REVERSE
        }
        return match.copy(direction = direction)
    }

    fun updatedEma(previous: GuideState, current: MatchResult, config: GuideConfig): Double {
        val previousMatch = previous.lastMatch ?: return previous.emaDeltaMeters
        val delta = current.projectedMeters - previousMatch.projectedMeters
        return config.emaAlpha * delta + (1.0 - config.emaAlpha) * previous.emaDeltaMeters
    }
}
