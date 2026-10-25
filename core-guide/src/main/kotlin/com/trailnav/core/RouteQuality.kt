package com.trailnav.core

import kotlin.math.abs

/** Deterministic route statistics captured when a GPX is preprocessed. */
data class RouteQuality(
    val pointCount: Int,
    val meanSpacingMeters: Double,
    val maxSpacingMeters: Double,
    val shortSegmentRatio: Double,
    val gapOver50Count: Int,
    val gapOver100Count: Int,
    val smoothedMaxAbsSlope: Double,
    val etaMinSegmentSpeedMps: Double,
    val etaClampedSegmentCount: Int,
    val etaClampedLengthRatio: Double,
    val smoothingMaxDeltaMeters: Double,
) {
    companion object {
        val EMPTY = RouteQuality(
            pointCount = 0,
            meanSpacingMeters = 0.0,
            maxSpacingMeters = 0.0,
            shortSegmentRatio = 0.0,
            gapOver50Count = 0,
            gapOver100Count = 0,
            smoothedMaxAbsSlope = 0.0,
            etaMinSegmentSpeedMps = toblerSpeedMetersPerSecond(0.0),
            etaClampedSegmentCount = 0,
            etaClampedLengthRatio = 0.0,
            smoothingMaxDeltaMeters = 0.0,
        )
    }
}

internal fun calculateRouteQuality(
    route: RouteModel,
    filteredPointCount: Int,
    config: GuideConfig,
): RouteQuality {
    val segmentLengths = if (filteredPointCount < 2) {
        emptyList()
    } else {
        route.cumulativeMeters.zipWithNext { start, end -> end - start }
    }
    val totalLengthMeters = segmentLengths.sum()
    val elevationUsable = routeHasUsableEtaElevation(route)
    val slopes = segmentLengths.indices.map { index ->
        etaSegmentSlope(route, index, config.etaSlopeWindowMeters)
    }
    val rawSpeeds = slopes.map(::toblerSpeedMetersPerSecond)
    val clampedIndices = rawSpeeds.indices.filter { rawSpeeds[it] < config.etaMinSegmentSpeedMps }
    val clampedLengthMeters = clampedIndices.sumOf { segmentLengths[it] }
    // Raw and smoothed profiles share the retained route-point distance knots.
    // The absolute difference of their linear interpolants reaches its maximum at a knot.
    val smoothingDelta = if (elevationUsable) {
        route.elevationMeters.indices.maxOfOrNull { index ->
            abs(route.smoothedElevationMeters[index] - requireNotNull(route.elevationMeters[index]))
        } ?: 0.0
    } else {
        0.0
    }

    return RouteQuality(
        pointCount = filteredPointCount,
        meanSpacingMeters = segmentLengths.average().takeIf { segmentLengths.isNotEmpty() } ?: 0.0,
        maxSpacingMeters = segmentLengths.maxOrNull() ?: 0.0,
        shortSegmentRatio = if (segmentLengths.isEmpty()) 0.0 else {
            segmentLengths.count { it < config.routeQualityShortSegmentMeters }.toDouble() / segmentLengths.size
        },
        gapOver50Count = route.warnings.count { it.kind == "point-gap" },
        gapOver100Count = segmentLengths.count { it > config.routeQualityGapWarnMeters },
        smoothedMaxAbsSlope = if (elevationUsable) slopes.maxOfOrNull(::abs) ?: 0.0 else 0.0,
        etaMinSegmentSpeedMps = rawSpeeds.minOrNull() ?: toblerSpeedMetersPerSecond(0.0),
        etaClampedSegmentCount = clampedIndices.size,
        etaClampedLengthRatio = if (totalLengthMeters <= 0.0) 0.0 else clampedLengthMeters / totalLengthMeters,
        smoothingMaxDeltaMeters = smoothingDelta,
    )
}
