package com.trailnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteEtaTest {
    private fun route(
        distances: List<Double> = listOf(0.0, 1_000.0),
        elevations: List<Double>? = null,
        peaks: List<Peak> = emptyList(),
        waypoints: List<Waypoint> = emptyList(),
    ): RouteModel {
        val points = distances.map { EnuPoint(it, 0.0) }
        val elevationValues = elevations ?: emptyList()
        return RouteModel(
            sourcePoints = distances.map { GeoPoint(0.0, it / 111_320.0) },
            points = points,
            cumulativeMeters = distances,
            simplifiedPoints = points,
            simplifiedCumulativeMeters = distances,
            turns = emptyList(),
            spatialIndex = SpatialIndex.empty(),
            warnings = emptyList(),
            elevationMeters = elevationValues,
            smoothedElevationMeters = elevationValues,
            elevationUse = if (elevations == null) ElevationUse(false, "absent") else ElevationUse(true, "ok"),
            peaks = peaks,
            waypoints = waypoints,
        )
    }

    private fun estimate(
        route: RouteModel,
        projectedMeters: Double,
        direction: ProgressDirection = ProgressDirection.FORWARD,
        arrived: Boolean = false,
        config: GuideConfig = GuideConfig(),
    ): RouteTargetEstimate? =
        RouteEtaTracker(route, config).estimate(projectedMeters, direction, arrived)

    @Test
    fun toblerReferenceValuesMatchAndSlopeChangesPrediction() {
        assertEquals(1.399095, toblerSpeedMetersPerSecond(0.0), 1e-6)
        assertEquals(1.666667, toblerSpeedMetersPerSecond(-0.05), 1e-6)
        assertEquals(0.985925, toblerSpeedMetersPerSecond(0.10), 1e-6)

        val flat = estimate(route(elevations = listOf(100.0, 100.0)), 0.0)!!
        val uphill = estimate(route(elevations = listOf(0.0, 100.0)), 0.0)!!
        val downhill = estimate(route(elevations = listOf(50.0, 0.0)), 0.0)!!

        assertEquals(714.74773, flat.remainingSeconds!!, 0.01)
        assertEquals(1_014.3, uphill.remainingSeconds!!, 0.1)
        assertEquals(600.0, downhill.remainingSeconds!!, 0.1)
        assertTrue(downhill.remainingSeconds!! < flat.remainingSeconds!!)
    }

    @Test
    fun shortSteepSegmentBetweenLongGapsDoesNotExplodeEta() {
        val target = estimate(
            route(
                distances = listOf(0.0, 150.0, 153.0, 330.0),
                elevations = listOf(0.0, 60.0, 40.0, 100.0),
            ),
            0.0,
        )!!

        val seconds = target.remainingSeconds!!
        assertTrue(seconds.isFinite())
        assertTrue(seconds < 86_400.0)
        assertEquals(850.95597, seconds, 0.01)

        val narrowWindow = estimate(
            route(
                distances = listOf(0.0, 150.0, 153.0, 330.0),
                elevations = listOf(0.0, 60.0, 40.0, 100.0),
            ),
            0.0,
            config = GuideConfig(etaSlopeWindowMeters = 1.0),
        )!!.remainingSeconds!!
        assertTrue(narrowWindow.isFinite())
        assertTrue(narrowWindow < 86_400.0)
    }

    @Test
    fun slopeWindowControlsSlopeAveraging() {
        val route = route(
            distances = listOf(0.0, 150.0, 153.0, 330.0),
            elevations = listOf(0.0, 60.0, 40.0, 100.0),
        )
        val narrow = estimate(
            route,
            0.0,
            config = GuideConfig(etaSlopeWindowMeters = 10.0),
        )!!.remainingSeconds!!
        val wide = estimate(
            route,
            0.0,
            config = GuideConfig(etaSlopeWindowMeters = 100.0),
        )!!.remainingSeconds!!

        assertTrue(narrow.isFinite() && wide.isFinite())
        assertTrue(kotlin.math.abs(narrow - wide) > 1.0)
    }

    @Test
    fun segmentSpeedFloorBoundsPredictedSeconds() {
        val route = route(distances = listOf(0.0, 1_000.0), elevations = listOf(0.0, 2_000.0))
        val defaultFloor = estimate(route, 0.0)!!.remainingSeconds!!
        val lowerFloor = estimate(
            route,
            0.0,
            config = GuideConfig(
                etaMinSegmentSpeedMps = 0.05,
                etaMinAverageSpeedMps = 0.01,
            ),
        )!!.remainingSeconds!!

        assertTrue(defaultFloor.isFinite())
        assertTrue(lowerFloor > defaultFloor)
        assertTrue(defaultFloor <= 1_000.0 / 0.15)
        assertTrue(lowerFloor <= 1_000.0 / 0.05)
    }

    @Test
    fun totalAboveCapIsUnavailable() {
        val route = route()
        val rawSeconds = estimate(route, 0.0)!!.remainingSeconds!!
        val justBelow = estimate(
            route,
            0.0,
            config = GuideConfig(etaMaxTotalSeconds = rawSeconds - 0.01),
        )!!
        val atLimit = estimate(
            route,
            0.0,
            config = GuideConfig(etaMaxTotalSeconds = rawSeconds),
        )!!
        val justAbove = estimate(
            route,
            0.0,
            config = GuideConfig(etaMaxTotalSeconds = rawSeconds + 0.01),
        )!!

        assertNull(justBelow.remainingSeconds)
        assertEquals("total-exceeds-max", justBelow.etaUnavailableReason)
        assertEquals(1_000.0, justBelow.remainingMeters)
        assertEquals(rawSeconds, atLimit.remainingSeconds!!, 1e-6)
        assertNull(atLimit.etaUnavailableReason)
        assertEquals(rawSeconds, justAbove.remainingSeconds!!, 1e-6)
        assertNull(justAbove.etaUnavailableReason)
    }

    @Test
    fun averageSpeedBelowMinimumIsUnavailable() {
        val route = route(distances = listOf(0.0, 1_000.0), elevations = listOf(0.0, 400.0))
        val target = estimate(
            route,
            0.0,
            config = GuideConfig(
                etaMinSegmentSpeedMps = 0.01,
                etaMinAverageSpeedMps = 0.5,
            ),
        )!!
        val defaultSettingsTarget = estimate(route, 0.0)!!

        assertNull(target.remainingSeconds)
        assertEquals("average-speed-below-min", target.etaUnavailableReason)
        assertEquals(1_000.0, target.remainingMeters)
        assertTrue(defaultSettingsTarget.remainingSeconds!!.isFinite())
        assertNull(defaultSettingsTarget.etaUnavailableReason)
    }

    @Test
    fun observationOverClampedSegmentIsExcludedFromCorrection() {
        val distances = listOf(
            0.0, 10.0, 300.0, 300.6, 301.2, 301.8, 302.4, 303.0, 303.6, 304.2, 600.0,
        )
        val elevations = listOf(
            0.0, 0.0, 0.0, 100.0, 200.0, 300.0, 400.0, 500.0, 600.0, 700.0, 700.0,
        )
        val tracker = RouteEtaTracker(
            route(distances = distances, elevations = elevations),
            GuideConfig(etaMinWindowMeters = 0.0, etaSlopeWindowMeters = 0.1),
        )
        tracker.observe(0L, 0.0, ProgressDirection.FORWARD, onRoute = true)
        tracker.observe(10_000L, 10.0, ProgressDirection.FORWARD, onRoute = true)
        tracker.observe(71_000L, 300.0, ProgressDirection.FORWARD, onRoute = true)
        repeat(7) { index ->
            tracker.observe(
                72_000L + index * 1_000L,
                300.6 + index * 0.6,
                ProgressDirection.FORWARD,
                onRoute = true,
            )
        }

        val target = tracker.estimate(304.2, ProgressDirection.FORWARD, arrived = false)!!

        assertTrue(target.correctionActive)
        assertEquals(toblerSpeedMetersPerSecond(0.0), target.correction, 1e-6)
        assertTrue(target.correction > 0.5)
    }

    @Test
    fun nonFiniteTotalIsUnavailable() {
        val target = estimate(
            route(distances = listOf(0.0, 1_000.0), elevations = listOf(0.0, Double.NaN)),
            0.0,
        )!!

        assertNull(target.remainingSeconds)
        assertEquals("non-finite", target.etaUnavailableReason)
        assertEquals(1_000.0, target.remainingMeters)
    }

    @Test
    fun nextSummitIsSelectedUntilRouteHighPointAndWaypointsTakePriority() {
        val summitRoute = route(
            distances = listOf(0.0, 100.0, 200.0, 300.0, 400.0, 500.0, 600.0),
            elevations = listOf(100.0, 180.0, 120.0, 200.0, 150.0, 250.0, 100.0),
            peaks = listOf(
                Peak(100.0, 180.0, 80.0),
                Peak(300.0, 200.0, 80.0),
                Peak(500.0, 250.0, 100.0),
            ),
        )

        val first = estimate(summitRoute, 0.0)!!
        assertEquals(TargetKind.NEXT_SUMMIT, first.kind)
        assertEquals("elevation-peak", first.source)
        assertEquals(100.0, first.remainingMeters)

        val second = estimate(summitRoute, 150.0)!!
        assertEquals(TargetKind.NEXT_SUMMIT, second.kind)
        assertEquals(150.0, second.remainingMeters)

        val afterRouteHighPoint = estimate(summitRoute, 500.0)!!
        assertEquals(TargetKind.DESTINATION, afterRouteHighPoint.kind)

        val waypointRoute = route(
            distances = listOf(0.0, 100.0, 200.0, 300.0, 400.0, 500.0, 600.0),
            elevations = listOf(100.0, 180.0, 120.0, 200.0, 150.0, 250.0, 100.0),
            peaks = summitRoute.peaks,
            waypoints = listOf(Waypoint("view", GeoPoint(0.0, 0.0), 200.0, 0.0)),
        )
        val waypoint = estimate(waypointRoute, 0.0)!!
        assertEquals(TargetKind.NEXT_SUMMIT, waypoint.kind)
        assertEquals("waypoint", waypoint.source)
        assertEquals(200.0, waypoint.remainingMeters)
    }

    @Test
    fun summitSwitchForcesDestinationEvenWhenWaypointIsAhead() {
        val routeAfterSwitchWaypoint = route(
            distances = listOf(0.0, 100.0, 200.0, 300.0, 400.0, 500.0, 600.0),
            elevations = listOf(100.0, 180.0, 120.0, 200.0, 150.0, 250.0, 100.0),
            waypoints = listOf(Waypoint("far summit", GeoPoint(0.0, 0.0), 550.0, 0.0)),
        )

        val target = estimate(routeAfterSwitchWaypoint, 510.0)!!
        assertEquals(TargetKind.DESTINATION, target.kind)
        assertEquals("destination", target.source)
    }

    @Test
    fun waypointsDefineSwitchWithoutElevationAndNoTargetsStayAtDestination() {
        val waypointRoute = route(
            distances = listOf(0.0, 100.0, 200.0, 300.0, 400.0),
            waypoints = listOf(
                Waypoint("one", GeoPoint(0.0, 0.0), 100.0, 0.0),
                Waypoint("two", GeoPoint(0.0, 0.0), 300.0, 0.0),
            ),
        )
        assertEquals(50.0, estimate(waypointRoute, 50.0)!!.remainingMeters)
        assertEquals(200.0, estimate(waypointRoute, 100.0)!!.remainingMeters)
        assertEquals(TargetKind.DESTINATION, estimate(waypointRoute, 300.0)!!.kind)

        val noTargets = estimate(route(), 200.0)!!
        assertEquals(TargetKind.DESTINATION, noTargets.kind)
        assertEquals("flat", noTargets.slopeSource)
    }

    @Test
    fun genericWaypointNamesMatchOnlyTheApprovedPattern() {
        listOf("경유지 #7", "경유지 2", "경유지#12", "  경유지   #  4  ").forEach {
            assertTrue(isGenericWaypointName(it), "expected generic waypoint name: '$it'")
        }
        listOf("바라산", "경유지 입구", "경유지 #7 쉼터", "").forEach {
            assertFalse(isGenericWaypointName(it), "expected named waypoint: '$it'")
        }
    }

    @Test
    fun estimatesCarryWaypointAndPeakLabelsWithoutChangingTravelValues() {
        val distances = listOf(0.0, 100.0, 200.0, 300.0, 400.0, 500.0, 600.0)
        val expectedSecondsPerHundredMeters = 100.0 / toblerSpeedMetersPerSecond(0.0)
        val namedRoute = route(
            distances = distances,
            waypoints = listOf(Waypoint("바라산", GeoPoint(0.0, 0.0), 300.0, 0.0)),
        )
        val named = estimate(namedRoute, 200.0)!!
        assertEquals(TargetKind.NEXT_SUMMIT, named.kind)
        assertEquals(TargetLabelKind.NAMED_WAYPOINT, named.labelKind)
        assertEquals("바라산", named.name)
        assertEquals(100.0, named.remainingMeters)
        assertEquals(expectedSecondsPerHundredMeters, named.remainingSeconds!!, 1e-6)

        val unnamedRoute = route(
            distances = distances,
            waypoints = listOf(Waypoint("경유지 #3", GeoPoint(0.0, 0.0), 300.0, 0.0)),
        )
        val unnamed = estimate(unnamedRoute, 200.0)!!
        assertEquals(TargetKind.NEXT_SUMMIT, unnamed.kind)
        assertEquals(TargetLabelKind.UNNAMED_WAYPOINT, unnamed.labelKind)
        assertNull(unnamed.name)
        assertEquals(named.remainingMeters, unnamed.remainingMeters)
        assertEquals(named.remainingSeconds, unnamed.remainingSeconds)

        val destinationWaypointRoute = route(
            distances = distances,
            waypoints = listOf(Waypoint("도착지", GeoPoint(0.0, 0.0), 590.0, 0.0)),
        )
        val destinationWaypoint = estimate(destinationWaypointRoute, 100.0)!!
        assertEquals(TargetKind.NEXT_SUMMIT, destinationWaypoint.kind)
        assertEquals(TargetLabelKind.DESTINATION, destinationWaypoint.labelKind)
        assertNull(destinationWaypoint.name)
        assertEquals(490.0, destinationWaypoint.remainingMeters)
        assertEquals(490.0 / toblerSpeedMetersPerSecond(0.0), destinationWaypoint.remainingSeconds!!, 1e-6)

        val peakRoute = route(
            distances = distances,
            peaks = listOf(Peak(300.0, 200.0, 100.0)),
        )
        val peak = estimate(peakRoute, 200.0)!!
        assertEquals(TargetKind.NEXT_SUMMIT, peak.kind)
        assertEquals(TargetLabelKind.ELEVATION_PEAK, peak.labelKind)
        assertNull(peak.name)
        assertEquals(named.remainingMeters, peak.remainingMeters)
        assertEquals(named.remainingSeconds, peak.remainingSeconds)

        val arrived = estimate(namedRoute, 590.0, arrived = true)!!
        assertEquals(TargetKind.DESTINATION, arrived.kind)
        assertEquals(TargetLabelKind.DESTINATION, arrived.labelKind)
        assertNull(arrived.name)
        assertEquals(0.0, arrived.remainingMeters)
        assertEquals(0.0, arrived.remainingSeconds)
    }

    @Test
    fun reverseSilencesTargetAndArrivalHasZeroDestinationEta() {
        val route = route()
        assertNull(estimate(route, 100.0, direction = ProgressDirection.REVERSE))

        val arrived = estimate(route, 100.0, arrived = true)!!
        assertEquals(TargetKind.DESTINATION, arrived.kind)
        assertEquals(0.0, arrived.remainingMeters)
        assertEquals(0.0, arrived.remainingSeconds)
    }

    @Test
    fun routeStatusKeepsOptionalTargetEmptyForExistingCoreCallers() {
        val route = route()
        val state = GuideState(
            route = route,
            lastMatch = MatchResult(
                distanceMeters = 0.0,
                projectedMeters = 100.0,
                segmentIndex = 0,
                projectedPoint = EnuPoint(100.0, 0.0),
                direction = ProgressDirection.FORWARD,
            ),
            direction = ProgressDirection.FORWARD,
        )

        assertNull(routeStatus(state)?.target)
    }

    @Test
    fun correctionActivationThreshold() {
        fun correctionFor(distanceMeters: Double): RouteTargetEstimate {
            val tracker = RouteEtaTracker(route())
            tracker.observe(0L, 0.0, ProgressDirection.FORWARD, onRoute = true)
            tracker.observe(60_000L, distanceMeters, ProgressDirection.FORWARD, onRoute = true)
            return tracker.estimate(distanceMeters, ProgressDirection.FORWARD, arrived = false)!!
        }

        val below = correctionFor(299.0)
        assertFalse(below.correctionActive)
        assertEquals(1.0, below.correction)

        val above = correctionFor(301.0)
        assertTrue(above.correctionActive)
    }

    @Test
    fun correctionClampsToConfiguredBounds() {
        val oneSecondOfFlatDistance = toblerSpeedMetersPerSecond(0.0)

        fun correctionFor(elapsedMillis: Long): RouteTargetEstimate {
            val tracker = RouteEtaTracker(
                route(distances = listOf(0.0, oneSecondOfFlatDistance)),
                GuideConfig(etaMinWindowMeters = 0.0),
            )
            tracker.observe(0L, 0.0, ProgressDirection.FORWARD, onRoute = true)
            tracker.observe(
                elapsedMillis,
                oneSecondOfFlatDistance,
                ProgressDirection.FORWARD,
                onRoute = true,
            )
            return tracker.estimate(0.0, ProgressDirection.FORWARD, arrived = false)!!
        }

        assertEquals(2.0, correctionFor(2_000L).correction, 1e-6)
        assertEquals(0.5, correctionFor(400L).correction, 1e-6)
        assertEquals(1.2, correctionFor(1_200L).correction, 1e-6)
    }

    @Test
    fun stoppedSegmentsDoNotAffectCorrection() {
        val oneSecondOfFlatDistance = toblerSpeedMetersPerSecond(0.0)
        val tracker = RouteEtaTracker(
            route(distances = listOf(0.0, oneSecondOfFlatDistance)),
            GuideConfig(etaMinWindowMeters = 0.0),
        )
        tracker.observe(0L, 0.0, ProgressDirection.FORWARD, onRoute = true)
        tracker.observe(1_000L, oneSecondOfFlatDistance, ProgressDirection.FORWARD, onRoute = true)
        val beforeStop = tracker.estimate(0.0, ProgressDirection.FORWARD, arrived = false)!!
        assertEquals(1.0, beforeStop.correction, 1e-6)

        repeat(6) { interval ->
            tracker.observe(
                1_000L + (interval + 1) * 50_000L,
                oneSecondOfFlatDistance,
                ProgressDirection.FORWARD,
                onRoute = true,
            )
        }

        val afterStop = tracker.estimate(0.0, ProgressDirection.FORWARD, arrived = false)!!
        assertEquals(1.0, afterStop.correction, 1e-6)
    }

    @Test
    fun observationGapLongerThanSixtySecondsIsExcluded() {
        val tracker = RouteEtaTracker(route(), GuideConfig(etaMinWindowMeters = 0.0))
        tracker.observe(0L, 0.0, ProgressDirection.FORWARD, onRoute = true)
        tracker.observe(120_000L, 100.0, ProgressDirection.FORWARD, onRoute = true)

        val target = tracker.estimate(100.0, ProgressDirection.FORWARD, arrived = false)!!
        assertFalse(target.correctionActive)
        assertEquals(1.0, target.correction)
    }

    @Test
    fun windowExpiry() {
        val tracker = RouteEtaTracker(route())
        tracker.observe(0L, 0.0, ProgressDirection.FORWARD, onRoute = true)
        tracker.observe(60_000L, 301.0, ProgressDirection.FORWARD, onRoute = true)
        assertTrue(tracker.estimate(301.0, ProgressDirection.FORWARD, arrived = false)!!.correctionActive)

        tracker.observe(660_001L, 600.0, ProgressDirection.FORWARD, onRoute = true)
        val expired = tracker.estimate(600.0, ProgressDirection.FORWARD, arrived = false)!!
        assertFalse(expired.correctionActive)
        assertEquals(1.0, expired.correction)
    }

    @Test
    fun identicalObservationOrderProducesIdenticalEstimates() {
        fun run(): RouteTargetEstimate? {
            val tracker = RouteEtaTracker(route(), GuideConfig(etaMinWindowMeters = 0.0))
            tracker.observe(0L, 0.0, ProgressDirection.FORWARD, onRoute = true)
            tracker.observe(20_000L, 40.0, ProgressDirection.FORWARD, onRoute = true)
            tracker.observe(40_000L, 80.0, ProgressDirection.FORWARD, onRoute = true)
            return tracker.estimate(80.0, ProgressDirection.FORWARD, arrived = false)
        }

        assertEquals(run(), run())
    }

    @Test
    fun etaIsIdenticalForIdenticalInputOrder() {
        val route = route(
            distances = listOf(0.0, 150.0, 153.0, 330.0),
            elevations = listOf(0.0, 60.0, 40.0, 100.0),
        )

        fun run(): RouteTargetEstimate? {
            val tracker = RouteEtaTracker(route, GuideConfig(etaMinWindowMeters = 0.0))
            tracker.observe(0L, 0.0, ProgressDirection.FORWARD, onRoute = true)
            tracker.observe(10_000L, 10.0, ProgressDirection.FORWARD, onRoute = true)
            tracker.observe(20_000L, 20.0, ProgressDirection.FORWARD, onRoute = true)
            return tracker.estimate(20.0, ProgressDirection.FORWARD, arrived = false)
        }

        assertEquals(run(), run())
    }
}
