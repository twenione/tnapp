package com.trailnav.core

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.hypot

internal object RouteMatcher {
    private const val COLOCATED_DISTANCE_METERS = 1.0
    private const val FORWARD_PROGRESS_TOLERANCE_METERS = 3.0
    private const val OPPOSING_DIRECTION_DEGREES = 150.0

    fun nearest(route: RouteModel, frame: SensorFrame, previousS: Double?, config: GuideConfig): MatchResult? {
        if (route.points.size < 2) return null
        val point = route.toEnu(GeoPoint(frame.lat, frame.lon))
        val candidates = candidateSegments(route, point, previousS, config)
        if (candidates.isEmpty()) return null
        var best: MatchResult? = null
        val matches = if (previousS == null) null else ArrayList<MatchResult>(candidates.size)
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
            matches?.add(candidate)
            if (best == null || candidate.distanceMeters < best.distanceMeters ||
                (candidate.distanceMeters == best.distanceMeters && candidate.projectedMeters < best.projectedMeters)) {
                best = candidate
            }
        }
        val nearest = best ?: return null
        val previousProgress = previousS ?: return nearest

        val colocatedOppositeLegs = requireNotNull(matches).filter { candidate ->
            candidate.distanceMeters <= nearest.distanceMeters + COLOCATED_DISTANCE_METERS &&
                candidate.segmentIndex != nearest.segmentIndex &&
                hasOpposingDirection(route, nearest.segmentIndex, candidate.segmentIndex)
        }
        val samePlace = listOf(nearest) + colocatedOppositeLegs
        val closestProgress = samePlace.minBy { kotlin.math.abs(it.projectedMeters - previousProgress) }
        val forwardProgress = samePlace
            .asSequence()
            .filter { it.projectedMeters >= previousProgress }
            .minByOrNull { it.projectedMeters - previousProgress }
        return if (
            forwardProgress != null &&
            forwardProgress.projectedMeters - previousProgress <=
            kotlin.math.abs(closestProgress.projectedMeters - previousProgress) + FORWARD_PROGRESS_TOLERANCE_METERS
        ) {
            forwardProgress
        } else {
            closestProgress
        }
    }

    private fun hasOpposingDirection(route: RouteModel, firstSegment: Int, secondSegment: Int): Boolean {
        val firstStart = route.points[firstSegment]
        val firstEnd = route.points[firstSegment + 1]
        val secondStart = route.points[secondSegment]
        val secondEnd = route.points[secondSegment + 1]
        val firstEast = firstEnd.east - firstStart.east
        val firstNorth = firstEnd.north - firstStart.north
        val secondEast = secondEnd.east - secondStart.east
        val secondNorth = secondEnd.north - secondStart.north
        val denominator = hypot(firstEast, firstNorth) * hypot(secondEast, secondNorth)
        if (denominator <= 0.0) return false
        val cosine = ((firstEast * secondEast + firstNorth * secondNorth) / denominator).coerceIn(-1.0, 1.0)
        val angleDegrees = acos(cosine) * 180.0 / PI
        return angleDegrees >= OPPOSING_DIRECTION_DEGREES
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

}
