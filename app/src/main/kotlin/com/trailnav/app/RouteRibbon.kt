package com.trailnav.app

import com.trailnav.core.GuideConfig
import com.trailnav.core.GuideResult
import com.trailnav.core.ProgressDirection
import com.trailnav.core.RouteModel
import kotlin.math.hypot

/**
 * Values needed by the route-relative ribbon.  The state is deliberately
 * independent from Android views so that the geometry can be verified on the
 * JVM and reused by the future Phase 3 voice consistency layer.
 */
data class RouteRibbonState(
    val perpendicularDistanceMeters: Double,
    val signedOffsetMeters: Double,
    val direction: ProgressDirection,
    val offRoute: Boolean,
    val enterBandMeters: Double,
    val exitBandMeters: Double,
    val accuracyRadiusMeters: Double,
    val remainingDistanceMeters: Double,
) {
    val side: RibbonSide
        get() = when {
            signedOffsetMeters > 0.01 -> RibbonSide.LEFT
            signedOffsetMeters < -0.01 -> RibbonSide.RIGHT
            else -> RibbonSide.CENTER
        }
}

enum class RibbonSide { LEFT, RIGHT, CENTER }

/** Pure adapter from a compiled guide result to the UI-facing ribbon state. */
object RouteRibbonCalculator {
    fun calculate(
        location: TrailLocation,
        result: GuideResult,
        route: RouteModel,
        config: GuideConfig,
    ): RouteRibbonState? {
        val match = result.nextState.lastMatch ?: return null
        val signed = signedOffsetMeters(location, route, match.segmentIndex, match.projectedPoint)
        val travelRelativeSigned = if (match.direction == ProgressDirection.REVERSE) -signed else signed
        return RouteRibbonState(
            perpendicularDistanceMeters = match.distanceMeters,
            signedOffsetMeters = travelRelativeSigned,
            direction = match.direction,
            offRoute = result.nextState.offRoute,
            enterBandMeters = config.offRouteEnterDistMeters,
            exitBandMeters = config.offRouteExitDistMeters,
            accuracyRadiusMeters = location.accuracyMeters.toDouble().coerceAtLeast(0.0),
            remainingDistanceMeters = (route.totalLengthMeters - match.projectedMeters).coerceAtLeast(0.0),
        )
    }

    private fun signedOffsetMeters(
        location: TrailLocation,
        route: RouteModel,
        segmentIndex: Int,
        projectedPoint: com.trailnav.core.EnuPoint,
    ): Double {
        if (route.points.size < 2) return 0.0
        val user = route.toEnu(com.trailnav.core.GeoPoint(location.latitude, location.longitude))
        val index = segmentIndex.coerceIn(0, route.points.lastIndex - 1)
        val start = route.points[index]
        val end = route.points[index + 1]
        val east = end.east - start.east
        val north = end.north - start.north
        val cross = east * (user.north - projectedPoint.north) - north * (user.east - projectedPoint.east)
        val length = hypot(east, north)
        return if (length <= 0.0) 0.0 else cross / length
    }
}
