package com.trailnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RouteQualityTest {
    @Test
    fun pointSpacingStatisticsMatchSyntheticRoute() {
        val route = route(listOf(0.0, 10.0, 20.0, 140.0, 145.0))

        assertEquals(5, route.quality.pointCount)
        assertEquals(36.25, route.quality.meanSpacingMeters, 0.02)
        assertEquals(120.0, route.quality.maxSpacingMeters, 0.02)
        assertEquals(0.25, route.quality.shortSegmentRatio, 0.001)
        assertEquals(1, route.quality.gapOver50Count)
        assertEquals(1, route.quality.gapOver100Count)
    }

    @Test
    fun gapWarnThresholdControlsCount() {
        val xml = gpx(listOf(0.0, 100.0, 200.1))
        val baseline = RouteModel.fromGpx(xml, GuideConfig(routeQualityGapWarnMeters = 1.0))
        val exactBoundary = baseline.cumulativeMeters[1]
        val justOverBoundary = baseline.cumulativeMeters[2] - baseline.cumulativeMeters[1]
        assertEquals(100.0, exactBoundary, 0.02)
        assertEquals(100.1, justOverBoundary, 0.02)

        val atBoundary = RouteModel.fromGpx(xml, GuideConfig(routeQualityGapWarnMeters = exactBoundary))
        val lowerThreshold = RouteModel.fromGpx(xml, GuideConfig(routeQualityGapWarnMeters = exactBoundary - 0.1))

        assertEquals(1, atBoundary.quality.gapOver100Count)
        assertEquals(2, lowerThreshold.quality.gapOver100Count)
    }

    @Test
    fun shortSegmentThresholdControlsRatio() {
        val xml = gpx(listOf(0.0, 4.0, 14.0))
        val sixMeterThreshold = RouteModel.fromGpx(xml, GuideConfig(routeQualityShortSegmentMeters = 6.0))
        val threeMeterThreshold = RouteModel.fromGpx(xml, GuideConfig(routeQualityShortSegmentMeters = 3.0))

        assertEquals(0.5, sixMeterThreshold.quality.shortSegmentRatio, 0.001)
        assertEquals(0.0, threeMeterThreshold.quality.shortSegmentRatio, 0.001)
    }

    @Test
    fun etaQualityUsesTheSameWindowAsEta() {
        val distances = listOf(0.0, 150.0, 153.0, 330.0)
        val elevations = listOf(0.0, 35.0, 39.0, 75.0)
        val narrowConfig = GuideConfig(etaSlopeWindowMeters = 10.0, elevationSpikeThresholdMeters = 1_000.0)
        val wideConfig = narrowConfig.copy(etaSlopeWindowMeters = 100.0)
        val narrow = route(distances, elevations, narrowConfig)
        val wide = route(distances, elevations, wideConfig)

        fun etaSegmentSpeedMinimum(route: RouteModel, config: GuideConfig): Double =
            (0 until route.points.lastIndex).minOf { index ->
                etaSegmentRawSpeedMetersPerSecond(route, index, config.etaSlopeWindowMeters)
            }

        assertEquals(etaSegmentSpeedMinimum(narrow, narrowConfig), narrow.quality.etaMinSegmentSpeedMps, 1e-9)
        assertEquals(etaSegmentSpeedMinimum(wide, wideConfig), wide.quality.etaMinSegmentSpeedMps, 1e-9)
        assertNotEquals(narrow.quality.etaMinSegmentSpeedMps, wide.quality.etaMinSegmentSpeedMps)
        val narrowEta = RouteEtaTracker(narrow, narrowConfig)
            .estimate(0.0, ProgressDirection.FORWARD, arrived = false)!!.remainingSeconds!!
        val wideEta = RouteEtaTracker(wide, wideConfig)
            .estimate(0.0, ProgressDirection.FORWARD, arrived = false)!!.remainingSeconds!!
        assertNotEquals(narrowEta, wideEta)
    }

    @Test
    fun qualityOfRouteWithoutElevationHasNeutralSlopeAndDelta() {
        val route = route(listOf(0.0, 50.0, 100.0))

        assertEquals(0.0, route.quality.smoothedMaxAbsSlope)
        assertEquals(0.0, route.quality.smoothingMaxDeltaMeters)
        assertEquals(toblerSpeedMetersPerSecond(0.0), route.quality.etaMinSegmentSpeedMps, 1e-9)
    }

    @Test
    fun realShapeSyntheticGapRouteReportsClampedSegments() {
        val config = GuideConfig(elevationSpikeThresholdMeters = 1_000.0)
        val route = route(
            listOf(0.0, 150.0, 153.0, 330.0),
            listOf(0.0, 100.0, 110.0, 200.0),
            config,
        )
        val rawSpeeds = (0 until route.points.lastIndex).map { index ->
            etaSegmentRawSpeedMetersPerSecond(route, index, config.etaSlopeWindowMeters)
        }
        val lengths = route.cumulativeMeters.zipWithNext { start, end -> end - start }
        val clamped = rawSpeeds.indices.filter { rawSpeeds[it] < config.etaMinSegmentSpeedMps }
        val expectedLengthRatio = clamped.sumOf { lengths[it] } / lengths.sum()

        assertEquals(2, route.quality.etaClampedSegmentCount)
        assertEquals(expectedLengthRatio, route.quality.etaClampedLengthRatio, 1e-9)
        assertEquals(150.0 / 330.0, route.quality.etaClampedLengthRatio, 0.01)
        assertTrue(route.elevationUse.used)
    }

    @Test
    fun smoothingMaxDeltaReportsLargestRawToSmoothedDifference() {
        val distances = (0..8).map { it * 10.0 }
        val flat = route(distances, List(9) { 100.0 })
        val bump = route(distances, listOf(100.0, 100.0, 100.0, 106.0, 100.0, 100.0, 100.0, 100.0, 100.0))

        assertEquals(0.0, flat.quality.smoothingMaxDeltaMeters, 1e-9)
        assertTrue(bump.quality.smoothingMaxDeltaMeters in 3.0..6.0, "delta=${bump.quality.smoothingMaxDeltaMeters}")
    }

    private fun route(
        distances: List<Double>,
        elevations: List<Double>? = null,
        config: GuideConfig = GuideConfig(),
    ): RouteModel = RouteModel.fromGpx(gpx(distances, elevations), config)

    private fun gpx(distances: List<Double>, elevations: List<Double>? = null): String =
        "<gpx><trk><trkseg>" + distances.mapIndexed { index, distance ->
            val lon = Math.toDegrees(distance / EARTH_RADIUS_METERS)
            val elevation = elevations?.let { "<ele>${it[index]}</ele>" }.orEmpty()
            "<trkpt lat=\"0\" lon=\"$lon\">$elevation</trkpt>"
        }.joinToString("") + "</trkseg></trk></gpx>"

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_008.8
    }
}
